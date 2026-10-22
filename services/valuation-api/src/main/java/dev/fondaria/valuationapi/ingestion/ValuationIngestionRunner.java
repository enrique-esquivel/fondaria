package dev.fondaria.valuationapi.ingestion;

import dev.fondaria.valuationapi.config.ValuationIngestionProperties;
import dev.fondaria.valuationapi.objectstore.ObjectStore;
import dev.fondaria.valuationapi.objectstore.ObjectStoreUnavailableException;
import dev.fondaria.valuationapi.objectstore.ObjectTooLargeException;
import dev.fondaria.valuationapi.strike.NavStrikeService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The asynchronous half of ingestion: submission returns {@code 202}, and this runs afterwards.
 *
 * <h2>Why a claimed row rather than a thread</h2>
 *
 * <p>A sweep claims batches by moving them to {@code VALIDATING} and stamping a lease. Handing the
 * work to an executor at submission time would be less machinery and strictly worse: a process that
 * dies mid-validation would strand its batch with nothing able to pick it up. A lease in a column
 * is recoverable by any instance, and re-validating is safe because it is a pure function of the
 * file's own bytes and of what is already accepted.
 *
 * <h2>An unreadable file is never a rejection</h2>
 *
 * <p>{@code REJECTED} is terminal, and a rejected valuation file means a fund goes unpriced until
 * somebody notices and asks for a redelivery. So a fault on our side — an unreachable object store,
 * an unexpected exception — leaves the batch claimed and lets its lease expire, which is also the
 * backoff. Only the file being genuinely wrong rejects it.
 */
@Component
public class ValuationIngestionRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(ValuationIngestionRunner.class);

  private final ValuationBatchRepository batches;
  private final AcceptedValuationRepository valuations;
  private final NavStrikeService navStrikeService;
  private final ObjectStore objectStore;
  private final ValuationBatchValidator validator;
  private final ValuationIngestionProperties properties;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public ValuationIngestionRunner(
      ValuationBatchRepository batches,
      AcceptedValuationRepository valuations,
      NavStrikeService navStrikeService,
      ObjectStore objectStore,
      ValuationBatchValidator validator,
      ValuationIngestionProperties properties,
      TransactionTemplate transactions,
      Clock clock) {
    this.batches = batches;
    this.valuations = valuations;
    this.navStrikeService = navStrikeService;
    this.objectStore = objectStore;
    this.validator = validator;
    this.properties = properties;
    this.transactions = transactions;
    this.clock = clock;
  }

  /**
   * One validation sweep. Scheduled, and also callable directly — a test drives it explicitly
   * rather than sleeping until a timer happens to fire.
   *
   * @return how many batches this sweep resolved
   */
  @Scheduled(
      fixedDelayString = "${fondaria.valuation-ingestion.poll-interval:5s}",
      initialDelayString = "${fondaria.valuation-ingestion.poll-interval:5s}")
  public int sweep() {
    Instant now = clock.instant();
    List<ValuationBatch> claimed =
        batches.claimForValidation(now, now.minus(properties.staleAfter()), properties.batchSize());
    int resolved = 0;
    for (ValuationBatch batch : claimed) {
      if (validate(batch)) {
        resolved++;
      }
    }
    return resolved;
  }

  /**
   * @return whether this batch reached an outcome. {@code false} means it stays claimed until its
   *     lease goes stale.
   */
  private boolean validate(ValuationBatch batch) {
    try {
      byte[] bytes = fetch(batch);
      requireChecksumMatches(batch, bytes);
      ValuationFile file = ValuationFileParser.parse(bytes);
      List<AcceptedValuation> toRecord = validator.validate(batch, file);
      return accept(batch, toRecord);
    } catch (ValuationRejectedException e) {
      LOGGER.info(
          "Valuation batch rejected, batchId={}, source={}, code={}",
          batch.batchId(),
          batch.source(),
          e.rejection().code());
      return batches.resolve(batch.rejected(e.rejection(), clock.instant()));
    } catch (ObjectStoreUnavailableException e) {
      LOGGER.error(
          "Could not read a valuation file; the batch stays claimed until its lease expires,"
              + " batchId={}, source={}",
          batch.batchId(),
          batch.source(),
          e);
      return false;
    } catch (RuntimeException e) {
      LOGGER.error(
          "Unexpected failure validating a valuation batch; it will be retried, batchId={},"
              + " source={}",
          batch.batchId(),
          batch.source(),
          e);
      return false;
    }
  }

  /**
   * Recording the valuations and marking the batch accepted are one transaction.
   *
   * <p>The alternative invents a window where a fund is priced by a batch nothing says was
   * accepted, or a batch says it accepted valuations that are not there. Neither is recoverable by
   * inspection afterwards, and both are about a number that moves every participant's balance in
   * the fund.
   *
   * <p>If the lease went stale and another pass owns this batch now, the whole transaction rolls
   * back — including the inserted valuations — because the pass that owns it will insert them
   * itself.
   */
  private boolean accept(ValuationBatch batch, List<AcceptedValuation> toRecord) {
    Boolean written =
        transactions.execute(
            status -> {
              valuations.insertAll(toRecord);
              boolean resolved = batches.resolve(batch.accepted(toRecord.size(), clock.instant()));
              if (!resolved) {
                status.setRollbackOnly();
              }
              return resolved;
            });
    if (Boolean.TRUE.equals(written)) {
      LOGGER.info(
          "Valuation batch accepted, batchId={}, source={}, valuationsRecorded={},"
              + " rowsRedelivered={}",
          batch.batchId(),
          batch.source(),
          toRecord.size(),
          batch.declaredRowCount() - toRecord.size());
      // Each fund's strike is caught individually. The batch is already ACCEPTED at this point —
      // the transaction above committed — so one fund's failure here (a transient LedgerPort blip
      // reading units in issue, most likely) must not stop the rest of this same batch's funds
      // from being struck. The fund that failed is not silently lost to a future retry that does
      // not exist yet: it is logged loudly, at ERROR, naming the exact fund and date, because
      // nothing today re-checks it automatically. A safe automatic recovery needs a real design
      // pass first — an unbounded blind retry can strike a stale price against units in issue that
      // moved since acceptance, and a bounded one can abandon a fund silently once its window
      // expires — so building one is deliberately left to a dedicated task, not guessed at here
      // (AGENTS.md §9). Until then, this ERROR line plus a manual Task 20.5 correction is the
      // recovery path.
      for (AcceptedValuation valuation : toRecord) {
        try {
          navStrikeService.strike(valuation.fundId(), valuation.valuationDate());
        } catch (RuntimeException e) {
          LOGGER.error(
              "Failed to strike a NAV immediately after accepting its valuation; no automatic"
                  + " retry exists yet (see this line's own comment) — this fund is unpriced for"
                  + " this date until a correction is made, batchId={}, fundId={},"
                  + " valuationDate={}",
              batch.batchId(),
              valuation.fundId(),
              valuation.valuationDate(),
              e);
        }
      }
    }
    return Boolean.TRUE.equals(written);
  }

  private byte[] fetch(ValuationBatch batch) {
    try {
      return objectStore
          .fetch(batch.objectKey())
          .orElseThrow(
              () ->
                  new ValuationRejectedException(
                      ValuationRejection.of(
                          ValuationRejectionCode.CHECKSUM_MISMATCH,
                          "No file has been uploaded to the object key this batch was submitted"
                              + " with. Upload the file, then submit it again.")));
    } catch (ObjectTooLargeException e) {
      throw new ValuationRejectedException(
          ValuationRejection.of(
              ValuationRejectionCode.MALFORMED_FILE,
              "The uploaded file is larger than this platform accepts.",
              Map.of("maximumBytes", e.maximumBytes())));
    }
  }

  /**
   * Check 1, before a single row is parsed — a file whose bytes did not arrive intact is not worth
   * reading, and a checksum computed over the stored object is the only way to know they did.
   *
   * <p>{@link MessageDigest#isEqual} rather than {@code String.equals}: it is constant-time, and
   * comparing a caller-supplied value against a computed one is exactly where that habit belongs.
   */
  private void requireChecksumMatches(ValuationBatch batch, byte[] bytes) {
    String actual = sha256Hex(bytes);
    if (!MessageDigest.isEqual(
        actual.getBytes(StandardCharsets.US_ASCII),
        batch.checksumSha256().getBytes(StandardCharsets.US_ASCII))) {
      // Neither checksum is rendered to the provider: the submitted one they already have,
      // and the computed one says only that the bytes differ, which the code already says.
      throw new ValuationRejectedException(
          ValuationRejection.of(
              ValuationRejectionCode.CHECKSUM_MISMATCH,
              "The uploaded file's SHA-256 is not the checksum submitted with it. Re-upload the"
                  + " file and submit the checksum of the bytes actually sent."));
    }
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}

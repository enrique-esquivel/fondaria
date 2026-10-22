package dev.fondaria.statementworker.schedule;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.CurrencyMismatchException;
import dev.fondaria.common.Money;
import dev.fondaria.statementworker.ParticipantKey;
import dev.fondaria.statementworker.config.StatementProperties;
import dev.fondaria.statementworker.contribution.ContributionSource;
import dev.fondaria.statementworker.contribution.ContributionTotals;
import dev.fondaria.statementworker.snapshot.AggregatedPositions;
import dev.fondaria.statementworker.snapshot.OpeningSnapshot;
import dev.fondaria.statementworker.snapshot.OpeningSnapshotRepository;
import dev.fondaria.statementworker.snapshot.PositionFileReader;
import dev.fondaria.statementworker.snapshot.SnapshotObjectReader;
import dev.fondaria.statementworker.snapshot.SnapshotObjectUnavailableException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * The scheduler role of {@code statement-worker} (Task 7.5b, {@code ADR-0022} §2): on the last
 * business day of a month, reads the prior month-end and this month-end snapshots plus the month's
 * {@code workflow.allotment}, and fans out one fully-computed {@code statement.jobs.v1} message per
 * open position.
 *
 * <h2>Reprocesses anything not verifiably settled; only a COMPLETED row is a gate</h2>
 *
 * <p>See {@link StatementRunRepository#openIfAbsent}'s javadoc. If this method is invoked twice for
 * the same period while it is still open — a genuine redelivery of {@code close.signed-off.v1}
 * after a crash here, or simply no row yet — it reads the same two immutable snapshot objects and
 * the same (append-only) {@code workflow.allotment} rows, computes the same numbers, and
 * republishes the same job messages. A worker rendering the same job twice writes the same bytes to
 * the same key and increments the Redis counter at most once (idempotent by construction). Skipping
 * a redelivery on the strength of "there is already a {@code RUNNING} row for this period" is the
 * one thing that could turn a partial crash into a silent gap, so that case is never
 * short-circuited.
 *
 * <p>A row already {@code COMPLETED}, in contrast, is a fact independently proven by {@link
 * StatementRunSettler} — the Redis counter reached {@code expected_count} — and is skipped rather
 * than redone. This is not just an optimisation for this method's own idempotence: {@code
 * eod-job}'s {@code SignoffService} re-publishes every unannounced {@code close_signoff} row on
 * every sweep (`ADR-0021`), so a month settled long ago can legitimately see its event again, and
 * redoing a whole fan-out that a verified-complete row already accounts for buys nothing.
 *
 * <h2>The one-fund-per-participant assumption, checked rather than assumed silently</h2>
 *
 * <p>{@code ParticipantStatement} ({@code contracts/openapi/balance-api.yaml}) carries no {@code
 * fundId}: v1 ships one fund per plan and one plan per participant enrolment ({@code ADR-0004},
 * {@code ADR-0012}), so a participant's positions collapse to at most one row in the closing
 * snapshot. This method verifies that collapsing loses nothing — the number of distinct
 * participants must equal the file's own {@code positionCount} — and refuses to guess if it ever
 * does not, rather than silently generating one statement that quietly drops a second fund's value.
 */
@Component
public class StatementScheduler {

  private static final Logger LOGGER = LoggerFactory.getLogger(StatementScheduler.class);

  /** No prior close exists before this sentinel — the genesis case for the contribution range. */
  private static final LocalDate EPOCH = LocalDate.of(1970, 1, 1);

  private final OpeningSnapshotRepository openingSnapshots;
  private final SnapshotObjectReader snapshotObjects;
  private final PositionFileReader positionFileReader;
  private final ContributionSource contributions;
  private final StatementRunRepository runs;
  private final RabbitTemplate rabbitTemplate;
  private final Clock clock;
  private final Duration publishConfirmTimeout;
  private final dev.fondaria.statementworker.render.RabbitMetrics metrics;

  public StatementScheduler(
      OpeningSnapshotRepository openingSnapshots,
      SnapshotObjectReader snapshotObjects,
      PositionFileReader positionFileReader,
      ContributionSource contributions,
      StatementRunRepository runs,
      RabbitTemplate rabbitTemplate,
      Clock clock,
      StatementProperties properties,
      dev.fondaria.statementworker.render.RabbitMetrics metrics) {
    this.openingSnapshots = openingSnapshots;
    this.snapshotObjects = snapshotObjects;
    this.positionFileReader = positionFileReader;
    this.contributions = contributions;
    this.runs = runs;
    this.rabbitTemplate = rabbitTemplate;
    this.clock = clock;
    this.publishConfirmTimeout = properties.publishConfirmTimeout();
    this.metrics = metrics;
  }

  public void handle(CloseSignedOffEvent event) {
    if (!event.lastBusinessDayOfMonth()) {
      LOGGER.debug(
          "close.signed-off.v1 for {} is not a month end; no statement run", event.businessDate());
      return;
    }

    String period = event.period();
    LocalDate closingBusinessDate = event.businessDate();
    LocalDate firstDayOfMonth = closingBusinessDate.withDayOfMonth(1);

    // A settled period is skipped, never a merely-open one. COMPLETED is written only once the
    // Redis counter independently confirms every job was rendered (StatementRunSettler) — a
    // terminal, verified fact, unlike RUNNING, which says nothing about whether publishing ever
    // finished and must always be reprocessed (see this method's own class javadoc). This is not
    // just a redelivery-of-the-same-crash optimisation: eod-job's SignoffService re-publishes
    // every unannounced close_signoff row on every sweep (ADR-0021), so a long-settled period's
    // event can legitimately arrive again in production, and redoing its whole fan-out for a
    // second time buys nothing a completed row does not already prove.
    if (runs.find(period).filter(run -> run.status() == StatementRunStatus.COMPLETED).isPresent()) {
      LOGGER.debug("period {} is already COMPLETED; skipping a redundant fan-out", period);
      return;
    }

    // The closing snapshot's own pass start — the read-consistency cutoff for
    // workflow.allotment. Missing means close.signed-off.v1 names a run workflow.snapshot_run
    // has no row for, a cross-service inconsistency no retry fixes — permanent, not transient.
    Instant postedBefore =
        reclassifyingPermanent(() -> openingSnapshots.startedAtOf(event.snapshotRunId()));

    Optional<OpeningSnapshot> opening = openingSnapshots.latestCompletedBefore(firstDayOfMonth);
    LocalDate openingBusinessDate = opening.map(OpeningSnapshot::businessDate).orElse(EPOCH);
    opening.ifPresent(
        o -> {
          YearMonth immediatelyPriorMonth = YearMonth.from(closingBusinessDate).minusMonths(1);
          if (!YearMonth.from(o.businessDate()).equals(immediatelyPriorMonth)) {
            // Not a hard invariant of the schema — a month with no completed snapshot at all
            // (a missed pass) would make latestCompletedBefore fall back further than one month
            // — but statementing against it would silently misstate a whole month's opening
            // balance, and that must surface as a loud failure, not a quiet wrong number.
            throw new StatementFanOutException(
                "period "
                    + period
                    + ": the opening snapshot is dated "
                    + o.businessDate()
                    + ", not in "
                    + immediatelyPriorMonth
                    + " — the immediately prior month has no completed snapshot to open from");
          }
        });
    AggregatedPositions openingPositions =
        opening
            .map(o -> reclassifyingPermanent(() -> readPositions(o.positionsObjectKey())))
            .orElseGet(() -> new AggregatedPositions(Map.of(), 0));

    AggregatedPositions closingPositions =
        reclassifyingPermanent(() -> readPositions(event.snapshotObjectKey()));
    if (closingPositions.positionCount() != event.snapshotPositionCount()) {
      throw new StatementFanOutException(
          "period "
              + period
              + ": the closing snapshot's own position count ("
              + closingPositions.positionCount()
              + ") does not match close.signed-off.v1's snapshotPositionCount ("
              + event.snapshotPositionCount()
              + ")");
    }
    if (closingPositions.valueByParticipant().size() != closingPositions.positionCount()) {
      throw new StatementFanOutException(
          "period "
              + period
              + ": "
              + closingPositions.positionCount()
              + " open positions collapse to only "
              + closingPositions.valueByParticipant().size()
              + " participants — a participant holds more than one fund, which Task 7.5b's"
              + " ParticipantStatement contract (no fundId) does not have a defined behaviour for");
    }

    Map<ParticipantKey, ContributionTotals> contributionTotals =
        reclassifyingPermanent(
            () -> contributions.totalsFor(openingBusinessDate, closingBusinessDate, postedBefore));

    runs.openIfAbsent(
        period, closingBusinessDate, clock.instant(), closingPositions.positionCount());

    List<CompletableFuture<CorrelationData.Confirm>> pending =
        new ArrayList<>(closingPositions.valueByParticipant().size());
    long publishStart = System.nanoTime();
    for (Map.Entry<ParticipantKey, Money> entry :
        closingPositions.valueByParticipant().entrySet()) {
      ParticipantKey key = entry.getKey();
      Money closingValue = entry.getValue();
      CurrencyCode currency = closingValue.currency();
      Money openingValue =
          openingPositions.valueByParticipant().getOrDefault(key, Money.of(0, currency));
      ContributionTotals totals =
          contributionTotals.getOrDefault(key, ContributionTotals.zero(currency));

      StatementJob job =
          new StatementJob(
              key.organizationId(),
              key.participantInternalRef(),
              period,
              event.signedOffAt(),
              openingValue.cents(),
              totals.employee().cents(),
              totals.employer().cents(),
              totals.voluntary().cents(),
              closingValue.cents(),
              currency);
      CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());
      rabbitTemplate.convertAndSend(
          StatementJobsTopic.NAME, key.asKey(), job.toJson(), correlationData);
      pending.add(correlationData.getFuture());
    }

    try {
      awaitAll(pending, period);
    } catch (RuntimeException e) {
      metrics.publishCompleted(StatementJobsTopic.NAME, System.nanoTime() - publishStart, true);
      throw e;
    }
    metrics.publishCompleted(StatementJobsTopic.NAME, System.nanoTime() - publishStart, false);
    LOGGER.info(
        "statement fan-out published for period {}: {} jobs, opening snapshot {}",
        period,
        pending.size(),
        opening
            .map(OpeningSnapshot::businessDate)
            .map(LocalDate::toString)
            .orElse("none (genesis)"));
  }

  private AggregatedPositions readPositions(String objectKey) {
    // PositionFileReader#read closes the stream it is given; no outer try-with-resources needed.
    return positionFileReader.read(snapshotObjects.open(objectKey));
  }

  /**
   * Runs {@code action}, reclassifying a known-permanent failure — one redelivering the identical
   * bytes would reproduce identically, so retrying it is pointless — as {@link
   * StatementFanOutException}, which {@code KafkaConsumeConfiguration} routes to the
   * container-stopping handler rather than bounded backoff.
   *
   * <p>Deliberately a narrow allow-list, not a catch-all: {@link
   * SnapshotObjectUnavailableException} (an object-store blip), {@link PublishFailedException} and
   * any {@code org.springframework.dao.DataAccessException} from a database call (a connectivity
   * blip) must keep propagating unchanged, because those genuinely are transient and retrying them
   * is exactly what should happen. Catching {@code RuntimeException} broadly here would silently
   * turn a momentary Postgres or object-store hiccup into a permanent halt, which is the opposite
   * failure mode.
   *
   * <ul>
   *   <li>{@link IllegalStateException} — {@code close.signed-off.v1} names a snapshot run {@code
   *       workflow.snapshot_run} has no row for.
   *   <li>{@link IllegalArgumentException} — {@code workflow.allotment.source} holds a value this
   *       service's {@code ContributionTotals} does not recognise.
   *   <li>{@link CurrencyMismatchException} — a participant's positions or contributions span more
   *       than one base currency, which v1's MXN-only scope does not expect.
   *   <li>{@link UncheckedIOException} — the snapshot object could be opened but not parsed: a
   *       truncated or corrupted body. Task 7.4 hashes the object at write time, so this is
   *       expected to be rare; when it happens, redelivering the same bytes fixes nothing.
   * </ul>
   */
  private <T> T reclassifyingPermanent(Supplier<T> action) {
    try {
      return action.get();
    } catch (IllegalStateException
        | IllegalArgumentException
        | CurrencyMismatchException
        | UncheckedIOException e) {
      throw new StatementFanOutException(
          "unexpected data while building the fan-out: " + e.getMessage(), e);
    }
  }

  private void awaitAll(List<CompletableFuture<CorrelationData.Confirm>> pending, String period) {
    try {
      CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
          .get(publishConfirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
      for (CompletableFuture<CorrelationData.Confirm> future : pending) {
        CorrelationData.Confirm confirm = future.get();
        if (confirm == null || !confirm.isAck()) {
          String reason = confirm != null ? confirm.getReason() : "no confirm returned";
          throw new PublishFailedException(
              "RabbitMQ nacked statement.jobs.v1 for period " + period + ": " + reason);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PublishFailedException(
          "interrupted while awaiting RabbitMQ confirmation of period " + period + "'s jobs", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new PublishFailedException(
          "RabbitMQ did not confirm all of period "
              + period
              + "'s statement.jobs.v1 messages within "
              + publishConfirmTimeout,
          e);
    }
  }
}

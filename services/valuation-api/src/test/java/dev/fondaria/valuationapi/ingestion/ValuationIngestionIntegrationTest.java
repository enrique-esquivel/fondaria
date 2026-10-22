package dev.fondaria.valuationapi.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.FundId;
import dev.fondaria.valuationapi.ageing.ValuationAgeingReport;
import dev.fondaria.valuationapi.ageing.ValuationAgeingService;
import dev.fondaria.valuationapi.objectstore.PresignedUpload;
import dev.fondaria.valuationapi.support.ValuationIntegrationTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The whole ingestion path against a real PostgreSQL and a real object store: upload slot, direct
 * upload, submission by reference, asynchronous validation, and the outcome on the batch.
 *
 * <p>Every test registers its own fund with a fresh {@link UUID} and asserts only over that fund.
 * Fixtures are reused across local runs (docs/ENGINEERING-RULES.md §4), so a count over {@code
 * workflow.valuation} as a whole would pass or fail on what earlier runs left behind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ValuationIngestionIntegrationTest extends ValuationIntegrationTest {

  private static final String SOURCE = "ACME-CUSTODY";

  @Autowired private ValuationBatchService batches;
  @Autowired private ValuationIngestionRunner runner;
  @Autowired private ValuationBatchRepository batchRepository;
  @Autowired private AcceptedValuationRepository valuations;
  @Autowired private ValuationAgeingService ageing;
  @Autowired private BusinessCalendar calendar;
  @Autowired private JdbcClient jdbc;
  @Autowired private Clock clock;

  @Test
  @DisplayName("a valid file is accepted and its valuation recorded")
  void acceptsAValidFile() {
    FundId fund = registerFund(businessDatesAgo(10));
    LocalDate date = businessDatesAgo(1);

    ValuationBatch batch = deliver(row(fund, date, 105_000_000L));

    assertThat(batch.status()).isEqualTo(ValuationBatchStatus.ACCEPTED);
    assertThat(batch.acceptedValuationCount()).hasValue(1);
    assertThat(valuations.find(fund, date).orElseThrow().reportedValueMinorUnits())
        .isEqualTo(105_000_000L);
  }

  /**
   * Task 20.3 acceptance criterion 1 — the real resubmission: the same bytes under the same object
   * key. Providers redeliver files routinely, so this is the normal path rather than an edge case.
   */
  @Test
  @DisplayName("resubmitting the identical file returns the original batch and one valuation")
  void resubmittingTheIdenticalFileReturnsTheOriginalBatch() {
    FundId fund = registerFund(businessDatesAgo(10));
    LocalDate date = businessDatesAgo(1);
    String csv = file(row(fund, date, 105_000_000L));

    String objectKey = upload(csv);
    ValuationBatch first = submitAndSweep(objectKey, csv, 1);
    assertThat(first.status()).isEqualTo(ValuationBatchStatus.ACCEPTED);
    assertThat(first.acceptedValuationCount()).hasValue(1);

    // The same submission again: same source, same key, same checksum.
    ValuationBatch second = submitAndSweep(objectKey, csv, 1);

    assertThat(second.batchId()).isEqualTo(first.batchId());
    assertThat(second.status()).isEqualTo(ValuationBatchStatus.ACCEPTED);
    // The second submission did not re-run validation and recorded nothing new, so exactly
    // one NAV can ever be struck for this fund and date (Task 20.4).
    assertThat(valuationRowsFor(fund, date)).isEqualTo(1);
  }

  @Test
  @DisplayName("the same numbers redelivered under a new object key are a new batch, but one NAV")
  void redeliveryUnderANewKeyIsANewBatchAndNoNewValuation() {
    FundId fund = registerFund(businessDatesAgo(10));
    LocalDate date = businessDatesAgo(1);

    ValuationBatch first = deliver(row(fund, date, 105_000_000L));
    // A fresh upload slot means a different object key, so this is a genuinely different
    // batch that happens to carry the same numbers.
    ValuationBatch second = deliver(row(fund, date, 105_000_000L));

    assertThat(second.batchId()).isNotEqualTo(first.batchId());
    assertThat(second.status()).isEqualTo(ValuationBatchStatus.ACCEPTED);
    // Accepted, but it recorded nothing: every row was an identical redelivery. This is
    // why an accepted batch's valuation count can be lower than its declared row count.
    assertThat(second.acceptedValuationCount()).hasValue(0);
    assertThat(valuationRowsFor(fund, date)).isEqualTo(1);
  }

  /** Task 20.3 acceptance criterion 2. */
  @Test
  @DisplayName("a valuation for an unknown fund is rejected, naming the fund and date")
  void rejectsAnUnknownFund() {
    FundId stranger = FundId.of(UUID.randomUUID());
    LocalDate date = businessDatesAgo(1);

    ValuationBatch batch = deliver(row(stranger, date, 105_000_000L));

    assertThat(batch.status()).isEqualTo(ValuationBatchStatus.REJECTED);
    assertThat(batch.rejection().orElseThrow().code())
        .isEqualTo(ValuationRejectionCode.UNKNOWN_FUND);
    assertThat(batch.rejection().orElseThrow().details())
        .containsEntry("fundId", stranger.value().toString())
        .containsEntry("valuationDate", date.toString());
    assertThat(valuations.find(stranger, date)).isEmpty();
  }

  /** Task 20.3 acceptance criterion 4. */
  @Test
  @DisplayName("a decimal amount is rejected end to end, and nothing is rounded into the store")
  void rejectsADecimalAmount() {
    FundId fund = registerFund(businessDatesAgo(10));
    LocalDate date = businessDatesAgo(1);

    ValuationBatch batch = deliver(fund.value() + "," + date + ",1050000.55,ACME-001");

    assertThat(batch.status()).isEqualTo(ValuationBatchStatus.REJECTED);
    assertThat(batch.rejection().orElseThrow().code())
        .isEqualTo(ValuationRejectionCode.DECIMAL_AMOUNT);
    // Nothing stored — in particular nothing rounded to 1050000 or 1050001.
    assertThat(valuations.find(fund, date)).isEmpty();
  }

  /** Task 20.3 acceptance criterion 5. */
  @Test
  @DisplayName("a valuation dated on a non-business date is refused")
  void refusesANonBusinessDate() {
    FundId fund = registerFund(businessDatesAgo(20));
    LocalDate weekend = mostRecentWeekendDay();

    ValuationBatch batch = deliver(row(fund, weekend, 105_000_000L));

    assertThat(batch.status()).isEqualTo(ValuationBatchStatus.REJECTED);
    assertThat(batch.rejection().orElseThrow().code())
        .isEqualTo(ValuationRejectionCode.NON_BUSINESS_DATE);
    assertThat(valuations.find(fund, weekend)).isEmpty();
  }

  @Test
  @DisplayName("a valuation contradicting a live one is refused as a correction")
  void refusesAContradictingValuation() {
    FundId fund = registerFund(businessDatesAgo(10));
    LocalDate date = businessDatesAgo(1);

    assertThat(deliver(row(fund, date, 105_000_000L)).status())
        .isEqualTo(ValuationBatchStatus.ACCEPTED);

    ValuationBatch correction = deliver(row(fund, date, 99_000_000L));

    assertThat(correction.status()).isEqualTo(ValuationBatchStatus.REJECTED);
    assertThat(correction.rejection().orElseThrow().code())
        .isEqualTo(ValuationRejectionCode.VALUATION_ALREADY_LIVE);
    // The original stands, untouched. A correction supersedes through Task 20.5; nothing
    // here ever overwrites a live valuation.
    assertThat(valuations.find(fund, date).orElseThrow().reportedValueMinorUnits())
        .isEqualTo(105_000_000L);
    assertThat(valuationRowsFor(fund, date)).isEqualTo(1);
  }

  @Test
  @DisplayName("a checksum that does not match the uploaded bytes is refused")
  void refusesAChecksumMismatch() {
    FundId fund = registerFund(businessDatesAgo(10));
    LocalDate date = businessDatesAgo(1);

    String objectKey = upload(file(row(fund, date, 105_000_000L)));
    ValuationBatch submitted =
        batches.submit(SOURCE, objectKey, sha256Hex("something else entirely"), 1);
    runner.sweep();

    ValuationBatch batch = batchRepository.find(submitted.batchId()).orElseThrow();
    assertThat(batch.status()).isEqualTo(ValuationBatchStatus.REJECTED);
    assertThat(batch.rejection().orElseThrow().code())
        .isEqualTo(ValuationRejectionCode.CHECKSUM_MISMATCH);
  }

  @Test
  @DisplayName("submitting an object key nobody uploaded to is refused")
  void refusesAnAbsentObject() {
    String objectKey = "valuations/" + SOURCE + "/" + UUID.randomUUID() + "-never-uploaded.csv";

    ValuationBatch submitted = batches.submit(SOURCE, objectKey, sha256Hex("anything"), 1);
    runner.sweep();

    assertThat(
            batchRepository
                .find(submitted.batchId())
                .orElseThrow()
                .rejection()
                .orElseThrow()
                .code())
        .isEqualTo(ValuationRejectionCode.CHECKSUM_MISMATCH);
  }

  @Test
  @DisplayName("a row count that disagrees with the declaration is refused")
  void refusesARowCountMismatch() {
    FundId fund = registerFund(businessDatesAgo(10));
    String csv = file(row(fund, businessDatesAgo(1), 105_000_000L));

    ValuationBatch batch = submitAndSweep(upload(csv), csv, 2);

    assertThat(batch.rejection().orElseThrow().code())
        .isEqualTo(ValuationRejectionCode.ROW_COUNT_MISMATCH);
  }

  @Test
  @DisplayName("a file valuing several funds is accepted as one batch")
  void acceptsAMultiFundFile() {
    LocalDate date = businessDatesAgo(1);
    FundId first = registerFund(businessDatesAgo(10));
    FundId second = registerFund(businessDatesAgo(10));

    ValuationBatch batch = deliver(row(first, date, 105_000_000L), row(second, date, 48_250_075L));

    assertThat(batch.status()).isEqualTo(ValuationBatchStatus.ACCEPTED);
    assertThat(batch.acceptedValuationCount()).hasValue(2);
    assertThat(valuations.find(second, date).orElseThrow().reportedValueMinorUnits())
        .isEqualTo(48_250_075L);
  }

  @Test
  @DisplayName("one bad row rejects the whole file, and the good rows are not recorded")
  void rejectsTheWholeFileWhenOneRowFails() {
    LocalDate date = businessDatesAgo(1);
    FundId good = registerFund(businessDatesAgo(10));
    FundId stranger = FundId.of(UUID.randomUUID());

    ValuationBatch batch = deliver(row(good, date, 105_000_000L), row(stranger, date, 1L));

    assertThat(batch.status()).isEqualTo(ValuationBatchStatus.REJECTED);
    // A partially accepted valuation file would leave some funds priced on this delivery
    // and some not, with nothing recording which.
    assertThat(valuations.find(good, date)).isEmpty();
  }

  @Test
  @DisplayName("a falling reported balance is accepted across a series, and never clamped")
  void acceptsAFallingSeries() {
    FundId fund = registerFund(businessDatesAgo(10));
    LocalDate first = businessDatesAgo(3);
    LocalDate second = businessDatesAgo(2);
    LocalDate third = businessDatesAgo(1);

    // Rising, then falling hard. docs/ENGINEERING-RULES.md §4: values go down, and a suite that
    // only ever
    // exercised rising prices would pass on a broken implementation.
    deliver(row(fund, first, 100_000_000L));
    deliver(row(fund, second, 110_000_000L));
    deliver(row(fund, third, 62_500_000L));

    assertThat(valuations.find(fund, third).orElseThrow().reportedValueMinorUnits())
        .isEqualTo(62_500_000L)
        .isLessThan(valuations.find(fund, second).orElseThrow().reportedValueMinorUnits());
  }

  /** Task 20.3 acceptance criterion 3. */
  @Test
  @DisplayName("a business date with no valuation appears on the ageing report")
  void reportsALateValuation() {
    LocalDate inception = businessDatesAgo(3);
    FundId fund = registerFund(inception);
    // Only the oldest of the four business dates is delivered; the three after it are late.
    deliver(row(fund, inception, 100_000_000L));

    List<LocalDate> missing = missingDatesFor(fund);

    assertThat(missing)
        .containsExactly(businessDatesAgo(2), businessDatesAgo(1), businessDatesAgo(0));
    assertThat(missing).doesNotContain(inception);
  }

  @Test
  @DisplayName("a fund valued on every business date since inception has nothing outstanding")
  void reportsNothingWhenUpToDate() {
    LocalDate inception = businessDatesAgo(2);
    FundId fund = registerFund(inception);
    deliver(row(fund, inception, 100_000_000L));
    deliver(row(fund, businessDatesAgo(1), 100_000_000L));
    deliver(row(fund, businessDatesAgo(0), 100_000_000L));

    assertThat(missingDatesFor(fund)).isEmpty();
  }

  @Test
  @DisplayName("a fund is never reported late for dates before its own inception")
  void neverReportsBeforeInception() {
    // Incepted today. Without the inception guard this would immediately be reported as
    // late for every business date in the lookback window.
    FundId fund = registerFund(businessDatesAgo(0));

    assertThat(missingDatesFor(fund)).containsExactly(businessDatesAgo(0));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Upload to a fresh slot, submit, sweep, read back. The whole path as a provider drives it, for
   * tests that do not care which object key was used.
   */
  private ValuationBatch deliver(String... rows) {
    String csv = file(rows);
    return submitAndSweep(upload(csv), csv, rows.length);
  }

  /** Requests a slot and PUTs the bytes to it, exactly as a provider would. */
  private String upload(String csv) {
    PresignedUpload slot = batches.createUploadSlot(SOURCE, "acme.csv");
    try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
      HttpResponse<Void> response =
          http.send(
              HttpRequest.newBuilder(URI.create(slot.url().toString()))
                  .timeout(Duration.ofSeconds(30))
                  .PUT(HttpRequest.BodyPublishers.ofString(csv, StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException("Upload failed with status " + response.statusCode());
      }
    } catch (IOException e) {
      throw new IllegalStateException("Upload failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Upload interrupted", e);
    }
    return slot.objectKey();
  }

  private ValuationBatch submitAndSweep(String objectKey, String csv, int declaredRowCount) {
    ValuationBatch submitted = batches.submit(SOURCE, objectKey, sha256Hex(csv), declaredRowCount);
    runner.sweep();
    return batchRepository.find(submitted.batchId()).orElseThrow();
  }

  private static String file(String... rows) {
    return ValuationFileParser.columnHeaderLine() + "\n" + String.join("\n", rows) + "\n";
  }

  private static String row(FundId fund, LocalDate date, long cents) {
    return fund.value() + "," + date + "," + cents + ",ACME-" + UUID.randomUUID();
  }

  private static String sha256Hex(String body) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** Only this fund's absences — the report is platform-wide and fixtures are reused. */
  private List<LocalDate> missingDatesFor(FundId fund) {
    return ageing.report().missing().stream()
        .filter(missing -> missing.fundId().equals(fund))
        .map(ValuationAgeingReport.MissingValuation::valuationDate)
        .toList();
  }

  /**
   * Registers a fund directly, because this service never writes the registry — publishing a fund
   * is contribution-api's, and {@code FundDirectory} is read-only by design.
   */
  private FundId registerFund(LocalDate inception) {
    FundId fundId = FundId.of(UUID.randomUUID());
    Instant now = clock.instant();
    jdbc.sql(
            """
            INSERT INTO reference.fund_identity (
                fund_id, unit_ledger_id, base_currency, unit_scale, inception_date,
                opening_nav_scaled, created_at)
            VALUES (:fundId, :unitLedgerId, 'MXN', 100000000, :inceptionDate, 1000000000, :now)
            """)
        .param("fundId", fundId.value())
        // Fresh per fund, and never colliding with another run's: the unit ledger id is
        // uniquely constrained across every fund that has ever existed (Task 20.1).
        .param("unitLedgerId", Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000))
        .param("inceptionDate", inception)
        .param("now", Timestamp.from(now))
        .update();
    jdbc.sql(
            """
            INSERT INTO reference.fund (
                fund_id, effective_from, version, name, vehicle_type, status, published_at)
            VALUES (:fundId, :effectiveFrom, 1, :name, 'CAJA_DE_AHORRO', 'ACTIVE', :now)
            """)
        .param("fundId", fundId.value())
        .param("effectiveFrom", inception)
        .param("name", "Test fund " + fundId.value())
        .param("now", Timestamp.from(now))
        .update();
    return fundId;
  }

  /**
   * Counts only this fund and date — never a total over the table (docs/ENGINEERING-RULES.md §4).
   */
  private int valuationRowsFor(FundId fund, LocalDate date) {
    return jdbc.sql(
            """
            SELECT count(*) FROM workflow.valuation
             WHERE fund_id = :fundId AND valuation_date = :valuationDate
            """)
        .param("fundId", fund.value())
        .param("valuationDate", date)
        .query(Integer.class)
        .single();
  }

  /**
   * {@code n} business dates back from the current one. Derived from the calendar rather than
   * hardcoded, so the suite does not start failing on a particular Monday.
   */
  private LocalDate businessDatesAgo(int n) {
    LocalDate date = calendar.businessDateOf(clock.instant());
    for (int i = 0; i < n; i++) {
      date = previousBusinessDate(date);
    }
    return date;
  }

  private LocalDate previousBusinessDate(LocalDate from) {
    LocalDate candidate = from.minusDays(1);
    while (!calendar.isBusinessDate(candidate)) {
      candidate = candidate.minusDays(1);
    }
    return candidate;
  }

  /** The most recent day that is not a business date, which is a weekend day. */
  private LocalDate mostRecentWeekendDay() {
    LocalDate date = calendar.businessDateOf(clock.instant());
    while (calendar.isBusinessDate(date)) {
      date = date.minusDays(1);
    }
    return date;
  }
}

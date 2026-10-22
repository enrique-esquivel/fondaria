package dev.fondaria.valuationapi.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.FundId;
import dev.fondaria.rules.FundStatus;
import dev.fondaria.valuationapi.support.InMemoryAcceptedValuationRepository;
import dev.fondaria.valuationapi.support.InMemoryFundDirectory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Checks 4 to 9 of {@code valuation-file-v1.md} §4. */
class ValuationBatchValidatorTest {

  /** Thursday 2026-08-20, before the 18:00 cut-off, so the business date is that Thursday. */
  private static final Instant NOW = Instant.parse("2026-08-20T15:00:00Z");

  private static final LocalDate THURSDAY = LocalDate.of(2026, 8, 20);
  private static final LocalDate SATURDAY = LocalDate.of(2026, 8, 22);
  private static final LocalDate HOLIDAY = LocalDate.of(2026, 8, 19);

  private InMemoryFundDirectory funds;
  private InMemoryAcceptedValuationRepository accepted;
  private ValuationBatchValidator validator;

  @BeforeEach
  void setUp() {
    funds = new InMemoryFundDirectory();
    accepted = new InMemoryAcceptedValuationRepository();
    validator =
        new ValuationBatchValidator(
            funds,
            accepted,
            new BusinessCalendar(ZoneId.of("UTC"), LocalTime.of(18, 0), Set.of(HOLIDAY)),
            Clock.fixed(NOW, ZoneId.of("UTC")));
  }

  @Test
  @DisplayName("a well-formed file yields one valuation to record per row")
  void acceptsAWellFormedFile() {
    FundId fund = funds.register(LocalDate.of(2026, 1, 1));

    List<AcceptedValuation> toRecord =
        validator.validate(batch(1), file(row(fund, THURSDAY, 105_000_000L)));

    assertThat(toRecord).hasSize(1);
    assertThat(toRecord.get(0).reportedValueMinorUnits()).isEqualTo(105_000_000L);
    assertThat(toRecord.get(0).fundId()).isEqualTo(fund);
  }

  @Test
  @DisplayName("check 4 — a row count that disagrees with the declaration is refused")
  void refusesARowCountMismatch() {
    FundId fund = funds.register(LocalDate.of(2026, 1, 1));

    assertThat(codeOf(batch(2), file(row(fund, THURSDAY, 1L))))
        .isEqualTo(ValuationRejectionCode.ROW_COUNT_MISMATCH);
  }

  @Test
  @DisplayName("check 5 — the same fund valued twice for the same date is refused")
  void refusesDuplicateRows() {
    FundId fund = funds.register(LocalDate.of(2026, 1, 1));

    // Same amount, deliberately: a file that says the same thing twice is a producer
    // fault, and guessing which row was meant is not this service's job.
    assertThat(codeOf(batch(2), file(row(fund, THURSDAY, 1L), row(fund, THURSDAY, 1L))))
        .isEqualTo(ValuationRejectionCode.DUPLICATE_ROW);
  }

  @Test
  @DisplayName("check 6 — a valuation dated in the future is refused as future, not as a holiday")
  void refusesAFutureValuationDate() {
    FundId fund = funds.register(LocalDate.of(2026, 1, 1));
    // Saturday 2026-08-22 is both in the future and a non-business date. "You cannot value
    // a fund on a date that has not happened" is the answer that leads somewhere; telling
    // the provider it is a weekend invites a resend dated Monday, which is also future.
    assertThat(codeOf(batch(1), file(row(fund, SATURDAY, 1L))))
        .isEqualTo(ValuationRejectionCode.FUTURE_VALUATION_DATE);
  }

  @Test
  @DisplayName("check 7 — a valuation dated on a past weekend is refused")
  void refusesAWeekendValuationDate() {
    FundId fund = funds.register(LocalDate.of(2026, 1, 1));
    LocalDate pastSaturday = LocalDate.of(2026, 8, 15);

    assertThat(codeOf(batch(1), file(row(fund, pastSaturday, 1L))))
        .isEqualTo(ValuationRejectionCode.NON_BUSINESS_DATE);
  }

  @Test
  @DisplayName("check 7 — a valuation dated on a configured holiday is refused")
  void refusesAHolidayValuationDate() {
    FundId fund = funds.register(LocalDate.of(2026, 1, 1));

    assertThat(codeOf(batch(1), file(row(fund, HOLIDAY, 1L))))
        .isEqualTo(ValuationRejectionCode.NON_BUSINESS_DATE);
  }

  @Test
  @DisplayName("check 8 — a valuation for an unregistered fund is refused, naming the fund")
  void refusesAnUnknownFund() {
    FundId stranger = FundId.of(UUID.randomUUID());

    ValuationRejection rejection = rejectionOf(batch(1), file(row(stranger, THURSDAY, 1L)));

    assertThat(rejection.code()).isEqualTo(ValuationRejectionCode.UNKNOWN_FUND);
    assertThat(rejection.details())
        .containsEntry("fundId", stranger.value().toString())
        .containsEntry("valuationDate", THURSDAY.toString());
  }

  @Test
  @DisplayName("check 8 — a fund is resolved as of the valuation's own date, never as of today")
  void resolvesTheFundAsOfTheValuationDate() {
    // Registered from 2026-08-18. A valuation dated 2026-08-17 predates the fund, and
    // resolving against today would wrongly accept it.
    FundId fund = funds.register(LocalDate.of(2026, 8, 18));

    assertThat(codeOf(batch(1), file(row(fund, LocalDate.of(2026, 8, 17), 1L))))
        .isEqualTo(ValuationRejectionCode.UNKNOWN_FUND);
    assertThat(validator.validate(batch(1), file(row(fund, LocalDate.of(2026, 8, 18), 1L))))
        .hasSize(1);
  }

  @Test
  @DisplayName("check 9 — redelivering an identical valuation is an idempotent no-op")
  void redeliveringAnIdenticalValuationRecordsNothing() {
    FundId fund = funds.register(LocalDate.of(2026, 1, 1));
    accepted.seed(fund, THURSDAY, 105_000_000L);

    List<AcceptedValuation> toRecord =
        validator.validate(batch(1), file(row(fund, THURSDAY, 105_000_000L)));

    assertThat(toRecord).isEmpty();
    assertThat(accepted.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("check 9 — a redelivery under a new provider reference is still the same valuation")
  void doesNotCompareTheProviderReference() {
    FundId fund = funds.register(LocalDate.of(2026, 1, 1));
    accepted.seed(fund, THURSDAY, 105_000_000L);

    ValuationFile restated =
        new ValuationFile(
            List.of(
                new ValuationFile.ValuationRow(
                    fund, THURSDAY, 105_000_000L, "A-COMPLETELY-DIFFERENT-REFERENCE")));

    assertThat(validator.validate(batch(1), restated)).isEmpty();
  }

  @Test
  @DisplayName("check 9 — a differing valuation is refused as a correction, never overwritten")
  void refusesAContradictingValuation() {
    FundId fund = funds.register(LocalDate.of(2026, 1, 1));
    accepted.seed(fund, THURSDAY, 105_000_000L);

    assertThat(codeOf(batch(1), file(row(fund, THURSDAY, 105_000_001L))))
        .isEqualTo(ValuationRejectionCode.VALUATION_ALREADY_LIVE);
    // The original is untouched: nothing here updates a live valuation.
    assertThat(accepted.find(fund, THURSDAY).orElseThrow().reportedValueMinorUnits())
        .isEqualTo(105_000_000L);
  }

  @Test
  @DisplayName("a falling reported balance is accepted, and nothing clamps it")
  void acceptsAFallingReportedBalance() {
    FundId fund = funds.register(LocalDate.of(2026, 1, 1));
    LocalDate wednesday = LocalDate.of(2026, 8, 18);
    accepted.seed(fund, wednesday, 105_000_000L);

    // Thursday's pool is worth less than Wednesday's. This must be an ordinary accepted
    // valuation — AGENTS.md §4: values go down, and nothing floors a loss at zero.
    List<AcceptedValuation> toRecord =
        validator.validate(batch(1), file(row(fund, THURSDAY, 71_500_000L)));

    assertThat(toRecord).hasSize(1);
    assertThat(toRecord.get(0).reportedValueMinorUnits()).isEqualTo(71_500_000L);
  }

  @Test
  @DisplayName("the whole file is refused when any single row fails")
  void rejectsTheWholeFileWhenOneRowFails() {
    FundId good = funds.register(LocalDate.of(2026, 1, 1));
    FundId stranger = FundId.of(UUID.randomUUID());

    assertThatThrownBy(
            () ->
                validator.validate(
                    batch(2), file(row(good, THURSDAY, 1L), row(stranger, THURSDAY, 1L))))
        .isInstanceOf(ValuationRejectedException.class);
    // Nothing from the good row was recorded either: a partially accepted valuation file
    // would leave some funds priced on this delivery and some not.
    assertThat(accepted.size()).isZero();
  }

  @Test
  @DisplayName("a fund registered as CLOSED still resolves, so its late valuations are accepted")
  void aClosedFundStillResolves() {
    // Status governs whether the ageing report EXPECTS a valuation, not whether a
    // valuation that arrives is valid. A fund closed on Friday still needs Thursday's
    // valuation to settle the units its participants held.
    FundId fund =
        funds.register(FundId.of(UUID.randomUUID()), LocalDate.of(2026, 1, 1), FundStatus.CLOSED);

    assertThat(validator.validate(batch(1), file(row(fund, THURSDAY, 1L)))).hasSize(1);
  }

  private static ValuationBatch batch(int declaredRowCount) {
    return ValuationBatch.received(
        "ACME-CUSTODY", "valuations/ACME-CUSTODY/file.csv", "a".repeat(64), declaredRowCount, NOW);
  }

  private static ValuationFile file(ValuationFile.ValuationRow... rows) {
    return new ValuationFile(List.of(rows));
  }

  private static ValuationFile.ValuationRow row(FundId fund, LocalDate date, long cents) {
    return new ValuationFile.ValuationRow(fund, date, cents, "ACME-001");
  }

  private ValuationRejectionCode codeOf(ValuationBatch batch, ValuationFile file) {
    return rejectionOf(batch, file).code();
  }

  private ValuationRejection rejectionOf(ValuationBatch batch, ValuationFile file) {
    try {
      validator.validate(batch, file);
      throw new AssertionError("Expected the batch to be rejected");
    } catch (ValuationRejectedException e) {
      return e.rejection();
    }
  }
}

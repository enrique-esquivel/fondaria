package dev.fondaria.valuationapi.ageing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.FundId;
import dev.fondaria.rules.FundStatus;
import dev.fondaria.valuationapi.config.ValuationIngestionProperties;
import dev.fondaria.valuationapi.support.InMemoryAcceptedValuationRepository;
import dev.fondaria.valuationapi.support.InMemoryFundDirectory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Task 20.3 acceptance criterion 3 — a late valuation appears on the ageing report.
 *
 * <p>A silently absent NAV is worse than a late one: every balance in the fund is quietly stale
 * until somebody notices. These tests are what make "somebody notices" structural.
 */
class ValuationAgeingServiceTest {

  /** Friday 2026-08-21, before cut-off. */
  private static final Instant NOW = Instant.parse("2026-08-21T15:00:00Z");

  private static final LocalDate MONDAY = LocalDate.of(2026, 8, 17);
  private static final LocalDate TUESDAY = LocalDate.of(2026, 8, 18);
  private static final LocalDate WEDNESDAY = LocalDate.of(2026, 8, 19);
  private static final LocalDate THURSDAY = LocalDate.of(2026, 8, 20);
  private static final LocalDate FRIDAY = LocalDate.of(2026, 8, 21);

  private InMemoryFundDirectory funds;
  private InMemoryAcceptedValuationRepository accepted;
  private ValuationAgeingService ageing;

  @BeforeEach
  void setUp() {
    funds = new InMemoryFundDirectory();
    accepted = new InMemoryAcceptedValuationRepository();
    ageing =
        new ValuationAgeingService(
            funds,
            accepted,
            new BusinessCalendar(ZoneId.of("UTC"), LocalTime.of(18, 0), Set.of()),
            properties(30, 2),
            Clock.fixed(NOW, ZoneId.of("UTC")));
  }

  @Test
  @DisplayName("a fund valued on every business date since inception has nothing outstanding")
  void reportsNothingWhenEveryBusinessDateIsCovered() {
    FundId fund = funds.register(MONDAY);
    for (LocalDate date : new LocalDate[] {MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY}) {
      accepted.seed(fund, date, 100L);
    }

    assertThat(ageing.report().missing()).isEmpty();
  }

  @Test
  @DisplayName("a business date with no valuation is reported, with how late it is")
  void reportsAMissingBusinessDate() {
    FundId fund = funds.register(MONDAY);
    accepted.seed(fund, MONDAY, 100L);
    accepted.seed(fund, TUESDAY, 100L);
    // Wednesday never arrived.
    accepted.seed(fund, THURSDAY, 100L);
    accepted.seed(fund, FRIDAY, 100L);

    ValuationAgeingReport report = ageing.report();

    assertThat(report.asOf()).isEqualTo(FRIDAY);
    assertThat(report.missing()).hasSize(1);
    ValuationAgeingReport.MissingValuation missing = report.missing().get(0);
    assertThat(missing.fundId()).isEqualTo(fund);
    assertThat(missing.valuationDate()).isEqualTo(WEDNESDAY);
    // Wednesday → Thursday → Friday: two business dates, not two calendar days by luck.
    assertThat(missing.businessDatesLate()).isEqualTo(2);
  }

  @Test
  @DisplayName("lateness is counted in business dates, so a weekend is not four days late")
  void countsLatenessInBusinessDates() {
    // A fund valued through Thursday, with Friday outstanding. Asking as of the following
    // Monday, Friday is one business date late — not three calendar days, which would make
    // every Monday look like an incident.
    ValuationAgeingService onMonday =
        new ValuationAgeingService(
            funds,
            accepted,
            new BusinessCalendar(ZoneId.of("UTC"), LocalTime.of(18, 0), Set.of()),
            properties(30, 2),
            Clock.fixed(Instant.parse("2026-08-24T15:00:00Z"), ZoneId.of("UTC")));

    FundId fund = funds.register(THURSDAY);
    accepted.seed(fund, THURSDAY, 100L);
    accepted.seed(fund, LocalDate.of(2026, 8, 24), 100L);

    ValuationAgeingReport report = onMonday.report();

    assertThat(report.missing()).hasSize(1);
    assertThat(report.missing().get(0).valuationDate()).isEqualTo(FRIDAY);
    assertThat(report.missing().get(0).businessDatesLate()).isEqualTo(1);
  }

  @Test
  @DisplayName("a fund is never expected to be valued before its own inception date")
  void doesNotExpectValuationsBeforeInception() {
    // Registered and incepted on Friday. Without the inception guard, a fund registered
    // today would immediately be reported as weeks late.
    FundId fund = funds.register(FRIDAY);

    ValuationAgeingReport report = ageing.report();

    assertThat(report.missing()).hasSize(1);
    assertThat(report.missing().get(0).valuationDate()).isEqualTo(FRIDAY);
  }

  @Test
  @DisplayName("weekends are never reported as missing")
  void neverReportsWeekends() {
    FundId fund = funds.register(MONDAY);

    assertThat(ageing.report().missing())
        .extracting(ValuationAgeingReport.MissingValuation::valuationDate)
        .containsExactly(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY);
  }

  @Test
  @DisplayName("a configured holiday is never reported as missing")
  void neverReportsAHoliday() {
    ValuationAgeingService withHoliday =
        new ValuationAgeingService(
            funds,
            accepted,
            new BusinessCalendar(ZoneId.of("UTC"), LocalTime.of(18, 0), Set.of(WEDNESDAY)),
            properties(30, 2),
            Clock.fixed(NOW, ZoneId.of("UTC")));
    funds.register(MONDAY);

    assertThat(withHoliday.report().missing())
        .extracting(ValuationAgeingReport.MissingValuation::valuationDate)
        .containsExactly(MONDAY, TUESDAY, THURSDAY, FRIDAY);
  }

  @Test
  @DisplayName("a CLOSED fund is not expected to be valued")
  void doesNotExpectValuationsForAClosedFund() {
    funds.register(FundId.of(UUID.randomUUID()), MONDAY, FundStatus.CLOSED);

    assertThat(ageing.report().missing()).isEmpty();
  }

  @Test
  @DisplayName("the lookback window bounds how far back absences are reported")
  void boundsTheLookbackWindow() {
    ValuationAgeingService shortWindow =
        new ValuationAgeingService(
            funds,
            accepted,
            new BusinessCalendar(ZoneId.of("UTC"), LocalTime.of(18, 0), Set.of()),
            properties(2, 2),
            Clock.fixed(NOW, ZoneId.of("UTC")));
    funds.register(MONDAY);

    // Two business dates back from Friday is Wednesday, so Monday and Tuesday fall out.
    assertThat(shortWindow.report().missing())
        .extracting(ValuationAgeingReport.MissingValuation::valuationDate)
        .containsExactly(WEDNESDAY, THURSDAY, FRIDAY);
  }

  @Test
  @DisplayName("an asOf in the future is refused — a date that has not happened cannot be late")
  void refusesAFutureAsOf() {
    assertThatThrownBy(() -> ageing.reportAsOf(LocalDate.of(2026, 8, 24)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("absences across several funds are reported oldest first")
  void ordersByDate() {
    FundId first = funds.register(THURSDAY);
    FundId second = funds.register(FRIDAY);
    accepted.seed(first, FRIDAY, 100L);

    assertThat(ageing.report().missing())
        .extracting(
            ValuationAgeingReport.MissingValuation::fundId,
            ValuationAgeingReport.MissingValuation::valuationDate)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(first, THURSDAY),
            org.assertj.core.groups.Tuple.tuple(second, FRIDAY));
  }

  private static ValuationIngestionProperties properties(int window, int alertAfter) {
    return new ValuationIngestionProperties(
        Duration.ofSeconds(5),
        10,
        Duration.ofMinutes(10),
        window,
        alertAfter,
        Duration.ofMinutes(15));
  }
}

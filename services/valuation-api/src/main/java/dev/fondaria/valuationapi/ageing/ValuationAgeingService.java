package dev.fondaria.valuationapi.ageing;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.FundId;
import dev.fondaria.rules.Fund;
import dev.fondaria.valuationapi.config.ValuationIngestionProperties;
import dev.fondaria.valuationapi.fund.FundDirectory;
import dev.fondaria.valuationapi.ingestion.AcceptedValuationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Walks each active fund's business dates looking for absences.
 *
 * <p>Every {@code ACTIVE} fund is expected to have exactly one accepted valuation for every
 * business date from its inception date onward ({@code O-21}: daily, settled in {@code BACKLOG.md}
 * § "Road to the demo"). A business date that has passed without one is late, and appears here.
 *
 * <p><b>This is a control, not a repair.</b> Nothing back-fills a missing valuation and no NAV is
 * ever interpolated between two dates. A price the platform invented is not a price anybody
 * reported, and inventing one would defeat the entire point of receiving valuations from outside.
 */
@Service
public class ValuationAgeingService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ValuationAgeingService.class);

  private final FundDirectory funds;
  private final AcceptedValuationRepository valuations;
  private final BusinessCalendar calendar;
  private final ValuationIngestionProperties properties;
  private final Clock clock;

  public ValuationAgeingService(
      FundDirectory funds,
      AcceptedValuationRepository valuations,
      BusinessCalendar calendar,
      ValuationIngestionProperties properties,
      Clock clock) {
    this.funds = funds;
    this.valuations = valuations;
    this.calendar = calendar;
    this.properties = properties;
    this.clock = clock;
  }

  /** The report as of the current business date. */
  public ValuationAgeingReport report() {
    return reportAsOf(calendar.businessDateOf(clock.instant()));
  }

  /**
   * @param asOf business dates are considered up to and including this date
   * @throws IllegalArgumentException {@code asOf} is in the future — a date that has not happened
   *     cannot be late
   */
  public ValuationAgeingReport reportAsOf(LocalDate asOf) {
    LocalDate today = calendar.businessDateOf(clock.instant());
    if (asOf.isAfter(today)) {
      throw new IllegalArgumentException("asOf must not be in the future");
    }

    // How far back to look. Unbounded would mean re-walking every business date since a
    // fund's inception on every call, which grows without limit and reports absences from
    // years ago that nobody is going to fill in. The window is configuration, and the
    // report says which window it used by way of its own asOf.
    LocalDate windowStart = businessDatesBefore(asOf, properties.ageingWindowBusinessDates());

    Instant now = clock.instant();
    List<ValuationAgeingReport.MissingValuation> missing = new ArrayList<>();
    for (Fund fund : funds.activeAsOf(asOf)) {
      missing.addAll(missingFor(fund, windowStart, asOf));
    }
    missing.sort(
        (left, right) -> {
          int byDate = left.valuationDate().compareTo(right.valuationDate());
          return byDate != 0 ? byDate : left.fundId().value().compareTo(right.fundId().value());
        });
    return new ValuationAgeingReport(asOf, now, List.copyOf(missing));
  }

  /**
   * The absences for one fund, derived by subtracting the dates it has from the business dates it
   * should have.
   *
   * <p>A fund is never expected to be valued before its own inception date, so the walk starts at
   * the later of the window and inception. Without that, registering a fund today would immediately
   * report it as months late.
   */
  private List<ValuationAgeingReport.MissingValuation> missingFor(
      Fund fund, LocalDate windowStart, LocalDate asOf) {
    FundId fundId = fund.fundId();
    LocalDate from = fund.inceptionDate().isAfter(windowStart) ? fund.inceptionDate() : windowStart;
    if (from.isAfter(asOf)) {
      return List.of();
    }

    Set<LocalDate> present = new HashSet<>(valuations.valuationDatesBetween(fundId, from, asOf));

    List<ValuationAgeingReport.MissingValuation> missing = new ArrayList<>();
    LocalDate date = calendar.isBusinessDate(from) ? from : calendar.nextBusinessDate(from);
    while (!date.isAfter(asOf)) {
      if (!present.contains(date)) {
        missing.add(
            new ValuationAgeingReport.MissingValuation(
                fundId, date, businessDatesBetween(date, asOf)));
      }
      date = calendar.nextBusinessDate(date);
    }
    return missing;
  }

  /**
   * Logs anything past the configured threshold.
   *
   * <p>Scheduled separately from the endpoint because an ageing report nobody opens is not a
   * control. A fund id and a date are safe to log — neither identifies a person
   * (docs/ENGINEERING-RULES.md §7).
   */
  @Scheduled(
      fixedDelayString = "${fondaria.valuation-ingestion.ageing-alert-interval:15m}",
      initialDelayString = "${fondaria.valuation-ingestion.ageing-alert-interval:15m}")
  public void alertOnLateValuations() {
    ValuationAgeingReport report = report();
    for (ValuationAgeingReport.MissingValuation late : report.missing()) {
      if (late.businessDatesLate() >= properties.ageingAlertBusinessDates()) {
        LOGGER.warn(
            "No valuation has been received for a fund on a past business date; every balance in"
                + " this fund is stale as at that date. fundId={}, valuationDate={},"
                + " businessDatesLate={}",
            late.fundId().value(),
            late.valuationDate(),
            late.businessDatesLate());
      }
    }
  }

  /** Business dates strictly between {@code from} and {@code to}, counting {@code to}. */
  private int businessDatesBetween(LocalDate from, LocalDate to) {
    int count = 0;
    LocalDate date = from;
    while (date.isBefore(to)) {
      date = calendar.nextBusinessDate(date);
      count++;
    }
    return count;
  }

  private LocalDate businessDatesBefore(LocalDate from, int businessDates) {
    LocalDate date = from;
    for (int i = 0; i < businessDates; i++) {
      date = previousBusinessDate(date);
    }
    return date;
  }

  /**
   * {@link BusinessCalendar} offers no "previous business date" — it is built to roll forward,
   * which is what a cut-off needs. Walking back a day at a time is correct and cheap at the scale
   * this is used (a bounded window, once per report).
   */
  private LocalDate previousBusinessDate(LocalDate date) {
    LocalDate candidate = date.minusDays(1);
    while (!calendar.isBusinessDate(candidate)) {
      candidate = candidate.minusDays(1);
    }
    return candidate;
  }
}

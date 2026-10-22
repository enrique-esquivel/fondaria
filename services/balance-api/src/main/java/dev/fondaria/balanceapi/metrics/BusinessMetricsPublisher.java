package dev.fondaria.balanceapi.metrics;

import dev.fondaria.balanceapi.fund.FundDirectory;
import dev.fondaria.balanceapi.fund.RegisteredFund;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.valuation.CachedNav;
import dev.fondaria.valuation.NavCache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodically exports business telemetry metrics to Prometheus via Micrometer (Task 30.10).
 *
 * <p><b>Cardinal Rule:</b> The metrics pipeline is observational and non-authoritative (AGENTS.md
 * §2, ADR-0008). Participants hold units, not pesos. A balance or AUM is never summed from
 * PostgreSQL. Authoritative units come from TigerBeetle's {@code UNITS_IN_ISSUE} contra account;
 * authoritative price comes from {@link NavCache} (converged on {@code reference.fund_nav}); and
 * AUM is computed via exact integer arithmetic ({@link Money#value(Units, Nav)}) before being
 * formatted to floating point at the export boundary.
 *
 * <p><b>No expensive per-sweep query</b> (BACKLOG row 26k). The participant headcount -- a {@code
 * COUNT(DISTINCT …)} over {@code master.participant} and {@code projection.participant_units} every
 * 15 s, which spilled to disk and was the most frequent statement in Postgres under load -- is
 * gone; contribution-api's {@code fondaria_participants_enrolled_total} counter replaces it. Fund
 * names and vehicle types are loaded once and reloaded only when the directory names a fund the
 * cache has not seen. What remains per sweep is the in-memory NAV and one batched TigerBeetle
 * lookup.
 */
public class BusinessMetricsPublisher {

  private static final Logger LOGGER = LoggerFactory.getLogger(BusinessMetricsPublisher.class);

  private final MeterRegistry meterRegistry;
  private final FundDirectory fundDirectory;
  private final NavCache navCache;
  private final LedgerPort ledger;
  private final AccountIdDeriver accountIds;
  private final JdbcClient jdbc;
  private final Clock clock;

  private final ConcurrentMap<FundId, FundGaugeHolder> fundGauges = new ConcurrentHashMap<>();
  private volatile Map<FundId, FundMetadata> fundMetadata = Map.of();
  private final AtomicLong snapshotTimestampSeconds = new AtomicLong(0L);

  public BusinessMetricsPublisher(
      MeterRegistry meterRegistry,
      FundDirectory fundDirectory,
      NavCache navCache,
      LedgerPort ledger,
      AccountIdDeriver accountIds,
      JdbcClient jdbc,
      Clock clock) {
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    this.fundDirectory = Objects.requireNonNull(fundDirectory, "fundDirectory must not be null");
    this.navCache = Objects.requireNonNull(navCache, "navCache must not be null");
    this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
    this.accountIds = Objects.requireNonNull(accountIds, "accountIds must not be null");
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");

    Gauge.builder(
            "fondaria_business_metrics_snapshot_timestamp_seconds",
            snapshotTimestampSeconds,
            AtomicLong::get)
        .description("Epoch seconds of the latest completed business snapshot sweep.")
        .register(meterRegistry);
  }

  /**
   * Periodic sweep scheduled at the configured interval.
   *
   * <p>If a sweep fails (e.g. temporary database or TigerBeetle connectivity blip), gauges maintain
   * their previous values and {@code snapshotTimestampSeconds} is not updated, surfacing an
   * increasing lag on the monitoring dashboard.
   */
  @Scheduled(
      fixedDelayString = "${fondaria.business-metrics.refresh-interval:15s}",
      initialDelay = 0)
  public void sweep() {
    try {
      publishMetrics();
    } catch (RuntimeException e) {
      LOGGER.warn("Business metrics sweep failed; keeping last known values", e);
    }
  }

  /** Publishes current snapshot of business telemetry. */
  public void publishMetrics() {
    List<RegisteredFund> funds = fundDirectory.listFunds();
    if (funds.isEmpty()) {
      snapshotTimestampSeconds.set(clock.instant().getEpochSecond());
      return;
    }

    Map<FundId, FundMetadata> metadataMap = fundMetadataFor(funds);

    List<AccountId> issueAccountIds =
        funds.stream()
            .map(
                f ->
                    accountIds.derive(
                        LedgerCodes.AccountCode.UNITS_IN_ISSUE, f.fundId().toString()))
            .toList();

    List<LedgerAccount> accounts = ledger.lookupAccounts(issueAccountIds);
    Map<AccountId, Units> unitsByAccountId = new HashMap<>();
    for (LedgerAccount account : accounts) {
      if (account instanceof LedgerAccount.Unit unitAccount) {
        unitsByAccountId.put(unitAccount.id(), unitAccount.balance());
      }
    }

    for (RegisteredFund fund : funds) {
      FundId fundId = fund.fundId();
      FundMetadata metadata = metadataMap.get(fundId);
      String fundName = metadata != null ? metadata.name() : fundId.value().toString();
      String vehicleType = metadata != null ? metadata.vehicleType() : "UNKNOWN";

      AccountId issueAccountId =
          accountIds.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fundId.toString());
      Units unitsInIssue = unitsByAccountId.getOrDefault(issueAccountId, Units.of(0L));

      Optional<CachedNav> liveNav = navCache.current(fundId);
      double navPesos = 0.0;
      long aumCents = 0L;
      double aumPesos = 0.0;
      if (liveNav.isPresent()) {
        Nav nav = liveNav.get().nav();
        navPesos = (double) nav.scaled() / 100_000_000.0;
        Money aum = Money.value(unitsInIssue, nav);
        aumCents = aum.cents();
        aumPesos = (double) aumCents / 100.0;
      }

      double unitsDouble = (double) unitsInIssue.scaled() / 100_000_000.0;

      FundGaugeHolder holder =
          fundGauges.computeIfAbsent(
              fundId, id -> new FundGaugeHolder(meterRegistry, id, fundName, vehicleType));
      holder.update(navPesos, unitsDouble, aumCents, aumPesos);
    }

    snapshotTimestampSeconds.set(clock.instant().getEpochSecond());
  }

  /**
   * The cached metadata, reloaded only when {@code funds} names one it lacks -- a newly registered
   * fund, or a previous load that failed. Fund names do not change sweep to sweep.
   */
  private Map<FundId, FundMetadata> fundMetadataFor(List<RegisteredFund> funds) {
    Map<FundId, FundMetadata> cached = fundMetadata;
    boolean missing = funds.stream().anyMatch(f -> !cached.containsKey(f.fundId()));
    if (!missing) {
      return cached;
    }
    Map<FundId, FundMetadata> loaded = loadFundMetadata();
    if (!loaded.isEmpty()) {
      fundMetadata = loaded;
      return loaded;
    }
    return cached;
  }

  private Map<FundId, FundMetadata> loadFundMetadata() {
    try {
      String sql =
          """
          SELECT DISTINCT ON (f.fund_id)
                 f.fund_id, f.name, f.vehicle_type
            FROM reference.fund f
           ORDER BY f.fund_id, f.effective_from DESC, f.version DESC
          """;
      // list(), never stream(): JdbcClient's stream() holds the ResultSet and its pooled
      // connection open until the Stream is closed, so an unclosed stream leaks one connection
      // per sweep and exhausts the Hikari pool within minutes.
      return jdbc
          .sql(sql)
          .query(
              (rs, rowNum) ->
                  Map.entry(
                      new FundId(rs.getObject("fund_id", UUID.class)),
                      new FundMetadata(rs.getString("name"), rs.getString("vehicle_type"))))
          .list()
          .stream()
          .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    } catch (RuntimeException e) {
      LOGGER.warn("Failed to load fund metadata from reference.fund; using defaults", e);
      return Map.of();
    }
  }

  record FundMetadata(String name, String vehicleType) {}

  static final class FundGaugeHolder {
    private final AtomicReference<Double> navPesos = new AtomicReference<>(0.0);
    private final AtomicReference<Double> unitsInIssue = new AtomicReference<>(0.0);
    private final AtomicLong aumCents = new AtomicLong(0L);
    private final AtomicReference<Double> aumPesos = new AtomicReference<>(0.0);

    FundGaugeHolder(MeterRegistry registry, FundId fundId, String fundName, String vehicleType) {
      Tags tags =
          Tags.of(
              "fund_id", fundId.value().toString(),
              "fund_name", fundName,
              "vehicle_type", vehicleType);

      Gauge.builder("fondaria_fund_nav_pesos", navPesos, ref -> ref.get())
          .tags(tags)
          .description(
              "Lagging operational snapshot — non-authoritative. Current struck NAV per unit in"
                  + " pesos.")
          .register(registry);

      Gauge.builder("fondaria_fund_units_in_issue", unitsInIssue, ref -> ref.get())
          .tags(tags)
          .description(
              "Lagging operational snapshot — non-authoritative. Total units in issue from"
                  + " UNITS_IN_ISSUE ledger account.")
          .register(registry);

      Gauge.builder("fondaria_fund_aum_cents", aumCents, AtomicLong::get)
          .tags(tags)
          .description(
              "Lagging operational snapshot — non-authoritative. Total fund AUM in MXN cents (units"
                  + " in issue × live NAV).")
          .register(registry);

      Gauge.builder("fondaria_fund_aum_pesos", aumPesos, ref -> ref.get())
          .tags(tags)
          .description(
              "Lagging operational snapshot — non-authoritative. Total fund AUM in MXN pesos.")
          .register(registry);
    }

    void update(double navPesosVal, double unitsInIssueVal, long aumCentsVal, double aumPesosVal) {
      navPesos.set(navPesosVal);
      unitsInIssue.set(unitsInIssueVal);
      aumCents.set(aumCentsVal);
      aumPesos.set(aumPesosVal);
    }
  }
}

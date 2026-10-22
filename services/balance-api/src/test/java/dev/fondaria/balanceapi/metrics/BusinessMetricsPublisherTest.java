package dev.fondaria.balanceapi.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.fondaria.balanceapi.fund.FundDirectory;
import dev.fondaria.balanceapi.fund.RegisteredFund;
import dev.fondaria.balanceapi.support.FakeLedgerPort;
import dev.fondaria.balanceapi.support.FakeNavCache;
import dev.fondaria.common.FundId;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class BusinessMetricsPublisherTest {

  private static final UUID NAMESPACE = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final AccountIdDeriver ACCOUNT_IDS = new AccountIdDeriver(NAMESPACE);

  private static final FundId FUND_1 =
      new FundId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
  private static final long UNIT_LEDGER_1 = 1001L;

  private SimpleMeterRegistry meterRegistry;
  private FakeNavCache navCache;
  private FakeLedgerPort ledgerPort;
  private JdbcClient jdbcClient;
  private Clock clock;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    navCache = new FakeNavCache();
    ledgerPort = new FakeLedgerPort();
    jdbcClient = mock(JdbcClient.class);
    clock = Clock.fixed(Instant.parse("2026-09-18T10:00:00Z"), ZoneOffset.UTC);
  }

  @Test
  @DisplayName("Publishes NAV, units in issue, exact integer AUM, and timestamp")
  void publishesAllGaugesAccurately() {
    FundDirectory fundDirectory = () -> List.of(new RegisteredFund(FUND_1, UNIT_LEDGER_1));

    // Live NAV: 10.50000000 pesos
    navCache.with(FUND_1, 10_50000000L, 1L);

    // TigerBeetle UNITS_IN_ISSUE: 200.00000000 units
    AccountId unitsInIssueAccount =
        ACCOUNT_IDS.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, FUND_1.toString());
    ledgerPort.withUnitAccount(
        unitsInIssueAccount, LedgerCodes.AccountCode.UNITS_IN_ISSUE, UNIT_LEDGER_1, 200_00000000L);

    // Mock JdbcClient for metadata and participants count
    setupJdbcMock("Fondo Ahorro", "CAJA_DE_AHORRO");

    BusinessMetricsPublisher publisher =
        new BusinessMetricsPublisher(
            meterRegistry, fundDirectory, navCache, ledgerPort, ACCOUNT_IDS, jdbcClient, clock);

    publisher.publishMetrics();

    // Check NAV gauge
    Gauge navGauge =
        meterRegistry.find("fondaria_fund_nav_pesos").tag("fund_id", FUND_1.toString()).gauge();
    assertThat(navGauge).isNotNull();
    assertThat(navGauge.value()).isEqualTo(10.5);

    // Check units in issue gauge
    Gauge unitsGauge =
        meterRegistry
            .find("fondaria_fund_units_in_issue")
            .tag("fund_id", FUND_1.toString())
            .gauge();
    assertThat(unitsGauge).isNotNull();
    assertThat(unitsGauge.value()).isEqualTo(200.0);

    // Check exact integer AUM in cents: 200 units * 10.5 pesos = 2,100 pesos = 210,000 cents
    Gauge aumCentsGauge =
        meterRegistry.find("fondaria_fund_aum_cents").tag("fund_id", FUND_1.toString()).gauge();
    assertThat(aumCentsGauge).isNotNull();
    assertThat(aumCentsGauge.value()).isEqualTo(210_000.0);

    // Check AUM in pesos: 2,100.0 pesos
    Gauge aumPesosGauge =
        meterRegistry.find("fondaria_fund_aum_pesos").tag("fund_id", FUND_1.toString()).gauge();
    assertThat(aumPesosGauge).isNotNull();
    assertThat(aumPesosGauge.value()).isEqualTo(2100.0);

    // Row 26k: the headcount gauge is gone (contribution-api's enrolment counter replaces it)
    assertThat(meterRegistry.find("fondaria_fund_participants_count").gauge()).isNull();

    // Check snapshot timestamp gauge
    Gauge tsGauge =
        meterRegistry.find("fondaria_business_metrics_snapshot_timestamp_seconds").gauge();
    assertThat(tsGauge).isNotNull();
    assertThat(tsGauge.value())
        .isEqualTo((double) Instant.parse("2026-09-18T10:00:00Z").getEpochSecond());
  }

  @Test
  @DisplayName("Correctly updates gauges when NAV falls (volatile market series)")
  void navDropReducesAum() {
    FundDirectory fundDirectory = () -> List.of(new RegisteredFund(FUND_1, UNIT_LEDGER_1));

    // Starting NAV: 10.00000000 pesos
    navCache.with(FUND_1, 10_00000000L, 1L);

    AccountId unitsInIssueAccount =
        ACCOUNT_IDS.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, FUND_1.toString());
    ledgerPort.withUnitAccount(
        unitsInIssueAccount, LedgerCodes.AccountCode.UNITS_IN_ISSUE, UNIT_LEDGER_1, 100_00000000L);

    setupJdbcMock("Fondo Volatil", "RETIREMENT_FUND");

    BusinessMetricsPublisher publisher =
        new BusinessMetricsPublisher(
            meterRegistry, fundDirectory, navCache, ledgerPort, ACCOUNT_IDS, jdbcClient, clock);

    publisher.publishMetrics();

    Gauge aumPesosGauge =
        meterRegistry.find("fondaria_fund_aum_pesos").tag("fund_id", FUND_1.toString()).gauge();
    assertThat(aumPesosGauge.value()).isEqualTo(1000.0);

    // NAV drops by 20% to 8.00000000 pesos
    navCache.with(FUND_1, 8_00000000L, 2L);

    publisher.publishMetrics();

    // AUM should now be 800 pesos
    assertThat(aumPesosGauge.value()).isEqualTo(800.0);
  }

  @Test
  @DisplayName("Handles unpriced fund gracefully without crashing")
  void unpricedFundReportsZeroNavAndAum() {
    FundDirectory fundDirectory = () -> List.of(new RegisteredFund(FUND_1, UNIT_LEDGER_1));

    // No NAV registered in navCache

    AccountId unitsInIssueAccount =
        ACCOUNT_IDS.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, FUND_1.toString());
    ledgerPort.withUnitAccount(
        unitsInIssueAccount, LedgerCodes.AccountCode.UNITS_IN_ISSUE, UNIT_LEDGER_1, 50_00000000L);

    setupJdbcMock("Unpriced Fund", "CAJA_DE_AHORRO");

    BusinessMetricsPublisher publisher =
        new BusinessMetricsPublisher(
            meterRegistry, fundDirectory, navCache, ledgerPort, ACCOUNT_IDS, jdbcClient, clock);

    publisher.publishMetrics();

    Gauge navGauge =
        meterRegistry.find("fondaria_fund_nav_pesos").tag("fund_id", FUND_1.toString()).gauge();
    assertThat(navGauge.value()).isEqualTo(0.0);

    Gauge aumPesosGauge =
        meterRegistry.find("fondaria_fund_aum_pesos").tag("fund_id", FUND_1.toString()).gauge();
    assertThat(aumPesosGauge.value()).isEqualTo(0.0);

    Gauge unitsGauge =
        meterRegistry
            .find("fondaria_fund_units_in_issue")
            .tag("fund_id", FUND_1.toString())
            .gauge();
    assertThat(unitsGauge.value()).isEqualTo(50.0);
  }

  @Test
  @DisplayName("Handles missing TigerBeetle account gracefully")
  void missingLedgerAccountReportsZeroUnits() {
    FundDirectory fundDirectory = () -> List.of(new RegisteredFund(FUND_1, UNIT_LEDGER_1));

    // NAV exists, but ledger account was never opened
    navCache.with(FUND_1, 10_00000000L, 1L);

    setupJdbcMock("New Fund", "CAJA_DE_AHORRO");

    BusinessMetricsPublisher publisher =
        new BusinessMetricsPublisher(
            meterRegistry, fundDirectory, navCache, ledgerPort, ACCOUNT_IDS, jdbcClient, clock);

    publisher.publishMetrics();

    Gauge unitsGauge =
        meterRegistry
            .find("fondaria_fund_units_in_issue")
            .tag("fund_id", FUND_1.toString())
            .gauge();
    assertThat(unitsGauge.value()).isEqualTo(0.0);

    Gauge aumPesosGauge =
        meterRegistry.find("fondaria_fund_aum_pesos").tag("fund_id", FUND_1.toString()).gauge();
    assertThat(aumPesosGauge.value()).isEqualTo(0.0);
  }

  @Test
  @DisplayName("Empty fund directory updates snapshot timestamp and completes cleanly")
  void emptyFundDirectoryHandledCleanly() {
    FundDirectory fundDirectory = List::of;

    BusinessMetricsPublisher publisher =
        new BusinessMetricsPublisher(
            meterRegistry, fundDirectory, navCache, ledgerPort, ACCOUNT_IDS, jdbcClient, clock);

    publisher.publishMetrics();

    Gauge tsGauge =
        meterRegistry.find("fondaria_business_metrics_snapshot_timestamp_seconds").gauge();
    assertThat(tsGauge.value())
        .isEqualTo((double) Instant.parse("2026-09-18T10:00:00Z").getEpochSecond());
  }

  @Test
  @DisplayName("Ledger failure preserves previous gauge values and halts timestamp advancement")
  void ledgerFailureKeepsPreviousValuesAndHaltsTimestamp() {
    FundDirectory fundDirectory = () -> List.of(new RegisteredFund(FUND_1, UNIT_LEDGER_1));

    navCache.with(FUND_1, 10_00000000L, 1L);
    AccountId unitsInIssueAccount =
        ACCOUNT_IDS.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, FUND_1.toString());
    ledgerPort.withUnitAccount(
        unitsInIssueAccount, LedgerCodes.AccountCode.UNITS_IN_ISSUE, UNIT_LEDGER_1, 100_00000000L);

    setupJdbcMock("Fondo Resiliente", "CAJA_DE_AHORRO");

    BusinessMetricsPublisher publisher =
        new BusinessMetricsPublisher(
            meterRegistry, fundDirectory, navCache, ledgerPort, ACCOUNT_IDS, jdbcClient, clock);

    publisher.publishMetrics();

    Gauge aumPesosGauge =
        meterRegistry.find("fondaria_fund_aum_pesos").tag("fund_id", FUND_1.toString()).gauge();
    Gauge tsGauge =
        meterRegistry.find("fondaria_business_metrics_snapshot_timestamp_seconds").gauge();

    double previousTimestamp = tsGauge.value();
    assertThat(aumPesosGauge.value()).isEqualTo(1000.0);

    // LedgerPort becomes unavailable
    ledgerPort.unavailable();

    // Scheduled sweep runs
    publisher.sweep();

    // Values remain unchanged
    assertThat(aumPesosGauge.value()).isEqualTo(1000.0);
    // Timestamp did NOT advance
    assertThat(tsGauge.value()).isEqualTo(previousTimestamp);
  }

  /**
   * JdbcClient's {@code stream()} holds its pooled connection until the Stream is closed; an
   * unclosed one leaked a connection per sweep and exhausted the pool (see {@code
   * BusinessMetricsPublisherIntegrationTest}, which {@code make verify} does not run).
   */
  @Test
  @DisplayName("Fund metadata is materialised with list(), never an unclosed stream()")
  void fundMetadataQueryDoesNotHoldAConnectionOpen() {
    FundDirectory fundDirectory = () -> List.of(new RegisteredFund(FUND_1, UNIT_LEDGER_1));
    JdbcClient.MappedQuerySpec<?> metaQuerySpec = setupJdbcMock("Fondo", "CAJA_DE_AHORRO");

    BusinessMetricsPublisher publisher =
        new BusinessMetricsPublisher(
            meterRegistry, fundDirectory, navCache, ledgerPort, ACCOUNT_IDS, jdbcClient, clock);

    publisher.publishMetrics();

    verify(metaQuerySpec).list();
    verify(metaQuerySpec, never()).stream();
  }

  @Test
  @DisplayName("Fund metadata is loaded once, not every sweep, and participants are never counted")
  void fundMetadataIsCachedAndNoParticipantQueryRuns() {
    FundDirectory fundDirectory = () -> List.of(new RegisteredFund(FUND_1, UNIT_LEDGER_1));
    navCache.with(FUND_1, 10_00000000L, 1L);
    JdbcClient.MappedQuerySpec<?> metaQuerySpec = setupJdbcMock("Fondo", "CAJA_DE_AHORRO", FUND_1);

    BusinessMetricsPublisher publisher =
        new BusinessMetricsPublisher(
            meterRegistry, fundDirectory, navCache, ledgerPort, ACCOUNT_IDS, jdbcClient, clock);

    publisher.publishMetrics();
    publisher.publishMetrics();
    publisher.publishMetrics();

    verify(metaQuerySpec, times(1)).list();
    verify(jdbcClient, never()).sql(contains("master.participant"));
    verify(jdbcClient, never()).sql(contains("projection."));
  }

  @Test
  @DisplayName("A newly registered fund triggers one metadata reload")
  void newFundReloadsMetadata() {
    FundId fund2 = new FundId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
    List<RegisteredFund> registered =
        new java.util.ArrayList<>(List.of(new RegisteredFund(FUND_1, UNIT_LEDGER_1)));
    FundDirectory fundDirectory = () -> List.copyOf(registered);
    // reference.fund has only FUND_1 at the first load, both funds by the reload.
    @SuppressWarnings({"unchecked", "rawtypes"})
    JdbcClient.MappedQuerySpec<Object> metaQuerySpec =
        (JdbcClient.MappedQuerySpec) setupJdbcMock("Fondo", "CAJA_DE_AHORRO", FUND_1);
    BusinessMetricsPublisher.FundMetadata meta =
        new BusinessMetricsPublisher.FundMetadata("Fondo", "CAJA_DE_AHORRO");
    when(metaQuerySpec.list())
        .thenReturn(List.of(java.util.Map.entry(FUND_1, meta)))
        .thenReturn(List.of(java.util.Map.entry(FUND_1, meta), java.util.Map.entry(fund2, meta)));

    BusinessMetricsPublisher publisher =
        new BusinessMetricsPublisher(
            meterRegistry, fundDirectory, navCache, ledgerPort, ACCOUNT_IDS, jdbcClient, clock);

    publisher.publishMetrics();
    publisher.publishMetrics();
    registered.add(new RegisteredFund(fund2, 1002L));
    publisher.publishMetrics();
    publisher.publishMetrics();

    verify(metaQuerySpec, times(2)).list();
    assertThat(
            meterRegistry
                .find("fondaria_fund_nav_pesos")
                .tag("fund_id", fund2.toString())
                .tag("fund_name", "Fondo")
                .gauge())
        .isNotNull();
  }

  /**
   * Mocks the {@code reference.fund} metadata query, returning one row per {@code knownFunds}.
   * Nothing else is mocked: any other query hits an unmocked {@code sql()} and returns null.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private JdbcClient.MappedQuerySpec<?> setupJdbcMock(
      String fundName, String vehicleType, FundId... knownFunds) {
    JdbcClient.StatementSpec metaSpec = mock(JdbcClient.StatementSpec.class);
    JdbcClient.MappedQuerySpec metaQuerySpec = mock(JdbcClient.MappedQuerySpec.class);

    when(jdbcClient.sql(org.mockito.ArgumentMatchers.contains("reference.fund")))
        .thenReturn(metaSpec);
    when(metaSpec.query(any(RowMapper.class))).thenReturn(metaQuerySpec);
    List<Object> rows = new java.util.ArrayList<>();
    for (FundId fund : knownFunds) {
      rows.add(
          java.util.Map.entry(
              fund, new BusinessMetricsPublisher.FundMetadata(fundName, vehicleType)));
    }
    when(metaQuerySpec.list()).thenReturn(rows);
    return metaQuerySpec;
  }
}

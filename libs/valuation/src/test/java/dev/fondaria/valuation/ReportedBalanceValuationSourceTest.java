package dev.fondaria.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link ReportedBalanceValuationSource} against in-memory fakes only — {@link
 * FakeFundRepository} and {@link FakeLedgerPort} hold no {@code DataSource} and cannot reach
 * PostgreSQL, so a passing {@code unitsInIssueComesFromTheLedgerPort} demonstrates the units count
 * came from {@link dev.fondaria.ledger.LedgerPort#lookupAccount}, the only place either fake can
 * source it from (Task 20.2 acceptance criterion 2).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ReportedBalanceValuationSourceTest {

  private static final AccountIdDeriver ACCOUNT_ID_DERIVER =
      new AccountIdDeriver(UUID.randomUUID());
  private static final LocalDate DATE = LocalDate.of(2026, 3, 2);

  @Test
  void the_worked_example_strikes_to_the_last_digit_end_to_end() {
    FundId fundId = FundId.of(UUID.randomUUID());
    Fund fund = fund(fundId, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN));
    FakeFundRepository funds = new FakeFundRepository(fund);
    FakeLedgerPort ledger = new FakeLedgerPort();
    ledger.givenUnitsInIssue(fundId, Units.of(1_000 * Units.SCALE));
    FakeReportedBalanceSource reportedBalances =
        new FakeReportedBalanceSource(fundId, DATE, Money.of(1_050_000, CurrencyCode.MXN));

    ReportedBalanceValuationSource source =
        new ReportedBalanceValuationSource(funds, ledger, ACCOUNT_ID_DERIVER, reportedBalances);

    Valuation valuation = source.valuationFor(fundId, DATE);

    assertThat(valuation.navPerUnit().nav()).isEqualTo(Nav.of(1_050_000_000L, CurrencyCode.MXN));
    assertThat(valuation.source()).isEqualTo("MODEL_B");
  }

  @Test
  void units_in_issue_comes_from_the_ledger_port() {
    FundId fundId = FundId.of(UUID.randomUUID());
    Fund fund = fund(fundId, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN));
    FakeFundRepository funds = new FakeFundRepository(fund);
    FakeLedgerPort ledger = new FakeLedgerPort();
    FakeReportedBalanceSource reportedBalances =
        new FakeReportedBalanceSource(fundId, DATE, Money.of(1_000_000, CurrencyCode.MXN));
    ReportedBalanceValuationSource source =
        new ReportedBalanceValuationSource(funds, ledger, ACCOUNT_ID_DERIVER, reportedBalances);

    ledger.givenUnitsInIssue(fundId, Units.of(500 * Units.SCALE));
    Valuation withFiveHundredUnits = source.valuationFor(fundId, DATE);

    ledger.givenUnitsInIssue(fundId, Units.of(1_000 * Units.SCALE));
    Valuation withOneThousandUnits = source.valuationFor(fundId, DATE);

    assertThat(withFiveHundredUnits.navPerUnit().unitsInIssue())
        .isEqualTo(Units.of(500 * Units.SCALE));
    assertThat(withOneThousandUnits.navPerUnit().unitsInIssue())
        .isEqualTo(Units.of(1_000 * Units.SCALE));
    assertThat(withFiveHundredUnits.navPerUnit().nav().scaled())
        .isGreaterThan(withOneThousandUnits.navPerUnit().nav().scaled());
  }

  @Test
  void a_fund_whose_units_in_issue_account_was_never_opened_strikes_at_its_opening_price() {
    FundId fundId = FundId.of(UUID.randomUUID());
    Nav openingNav = Nav.of(10 * Nav.SCALE, CurrencyCode.MXN);
    Fund fund = fund(fundId, openingNav);
    FakeFundRepository funds = new FakeFundRepository(fund);
    FakeLedgerPort ledger = new FakeLedgerPort(); // no UNITS_IN_ISSUE account provisioned
    FakeReportedBalanceSource reportedBalances =
        new FakeReportedBalanceSource(fundId, DATE, Money.of(0, CurrencyCode.MXN));
    ReportedBalanceValuationSource source =
        new ReportedBalanceValuationSource(funds, ledger, ACCOUNT_ID_DERIVER, reportedBalances);

    Valuation valuation = source.valuationFor(fundId, DATE);

    assertThat(valuation.navPerUnit().nav()).isEqualTo(openingNav);
    assertThat(valuation.navPerUnit().unitsInIssue().isZero()).isTrue();
  }

  @Test
  void a_falling_reported_balance_produces_a_lower_nav_with_nothing_clamping_it() {
    FundId fundId = FundId.of(UUID.randomUUID());
    Fund fund = fund(fundId, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN));
    FakeFundRepository funds = new FakeFundRepository(fund);
    FakeLedgerPort ledger = new FakeLedgerPort();
    ledger.givenUnitsInIssue(fundId, Units.of(1_000 * Units.SCALE));

    ReportedBalanceValuationSource source =
        new ReportedBalanceValuationSource(
            funds,
            ledger,
            ACCOUNT_ID_DERIVER,
            new FakeReportedBalanceSource(fundId, DATE, Money.of(1_050_000, CurrencyCode.MXN)));
    Valuation risingDay = source.valuationFor(fundId, DATE);

    ReportedBalanceValuationSource fallenSource =
        new ReportedBalanceValuationSource(
            funds,
            ledger,
            ACCOUNT_ID_DERIVER,
            new FakeReportedBalanceSource(fundId, DATE, Money.of(300_000, CurrencyCode.MXN)));
    Valuation fallenDay = fallenSource.valuationFor(fundId, DATE);

    assertThat(fallenDay.navPerUnit().nav().scaled())
        .isLessThan(risingDay.navPerUnit().nav().scaled());
  }

  @Test
  void an_unknown_fund_is_rejected() {
    FundId unknownFundId = FundId.of(UUID.randomUUID());
    ReportedBalanceValuationSource source =
        new ReportedBalanceValuationSource(
            new FakeFundRepository(),
            new FakeLedgerPort(),
            ACCOUNT_ID_DERIVER,
            new FakeReportedBalanceSource(unknownFundId, DATE, Money.of(0, CurrencyCode.MXN)));

    assertThatThrownBy(() -> source.valuationFor(unknownFundId, DATE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Fund fund(FundId fundId, Nav openingNav) {
    return new Fund(
        fundId,
        LocalDate.of(2026, 1, 1),
        1,
        "Caja de Ahorro Acme",
        "CAJA_DE_AHORRO",
        CurrencyCode.MXN,
        1001L,
        Units.SCALE,
        LocalDate.of(2026, 1, 1),
        openingNav,
        FundStatus.ACTIVE,
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  /** In-memory only — no {@code DataSource}, cannot reach PostgreSQL. */
  private static final class FakeFundRepository implements dev.fondaria.rules.FundRepository {
    private final Map<FundId, Fund> funds = new HashMap<>();

    FakeFundRepository(Fund... seeded) {
      for (Fund fund : seeded) {
        funds.put(fund.fundId(), fund);
      }
    }

    @Override
    public Optional<Fund> resolveAsOf(FundId fundId, LocalDate asOfDate) {
      return Optional.ofNullable(funds.get(fundId));
    }

    @Override
    public Fund publish(Fund candidate) {
      funds.put(candidate.fundId(), candidate);
      return candidate;
    }
  }

  /** In-memory only — no {@code DataSource}, cannot reach PostgreSQL. */
  private static final class FakeLedgerPort implements dev.fondaria.ledger.LedgerPort {
    private final Map<AccountId, LedgerAccount> accounts = new HashMap<>();

    void givenUnitsInIssue(FundId fundId, Units balance) {
      AccountId accountId =
          ACCOUNT_ID_DERIVER.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fundId.toString());
      accounts.put(
          accountId,
          new LedgerAccount.Unit(
              accountId, LedgerCodes.AccountCode.UNITS_IN_ISSUE, false, 1001L, balance));
    }

    @Override
    public Optional<LedgerAccount> lookupAccount(AccountId id) {
      return Optional.ofNullable(accounts.get(id));
    }

    @Override
    public dev.fondaria.ledger.AccountId createAccount(
        dev.fondaria.ledger.CreateAccountCommand cmd) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public java.util.List<LedgerAccount> lookupAccounts(java.util.List<AccountId> ids) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public dev.fondaria.ledger.TransferResult createTransfer(
        dev.fondaria.ledger.CreateTransferCommand cmd) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public java.util.List<dev.fondaria.ledger.TransferResult> createTransfers(
        java.util.List<dev.fondaria.ledger.CreateTransferCommand> cmds) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public java.util.List<dev.fondaria.ledger.TransferResult> createLinkedChain(
        java.util.List<dev.fondaria.ledger.CreateTransferCommand> cmds) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public Optional<dev.fondaria.ledger.LedgerTransfer> lookupTransfer(
        dev.fondaria.ledger.TransferId id) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public java.util.List<dev.fondaria.ledger.LedgerTransfer> accountTransfers(
        AccountId id, dev.fondaria.ledger.TransferQuery query) {
      throw new UnsupportedOperationException("not used by this path");
    }
  }

  /** In-memory only — stands in for Task 20.3's ingestion, which does not exist yet. */
  private static final class FakeReportedBalanceSource implements ReportedBalanceSource {
    private final Map<FundId, Map<LocalDate, Money>> values = new HashMap<>();

    FakeReportedBalanceSource(FundId fundId, LocalDate date, Money value) {
      values.computeIfAbsent(fundId, k -> new HashMap<>()).put(date, value);
    }

    @Override
    public Money reportedValueFor(FundId fund, LocalDate date) {
      Money value = values.getOrDefault(fund, Map.of()).get(date);
      if (value == null) {
        throw new IllegalArgumentException("No reported value for " + fund + " on " + date);
      }
      return value;
    }
  }
}

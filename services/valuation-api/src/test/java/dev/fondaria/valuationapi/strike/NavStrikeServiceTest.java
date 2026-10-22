package dev.fondaria.valuationapi.strike;

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
import dev.fondaria.rules.FundNav;
import dev.fondaria.rules.FundStatus;
import dev.fondaria.valuation.InMemoryNavCache;
import dev.fondaria.valuation.NavSnapshotStore;
import dev.fondaria.valuation.ReportedBalanceSource;
import dev.fondaria.valuationapi.fund.FundDirectory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link NavStrikeService} against in-memory fakes only — none of {@link
 * FakeFundDirectory}, {@link FakeLedgerPort}, {@link FakeReportedBalanceSource} or {@link
 * FakeFundNavRepository} holds a {@code DataSource}. The SQL and the schema are proven separately
 * by {@link JdbcFundNavRepositoryIntegrationTest} against a real PostgreSQL.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class NavStrikeServiceTest {

  private static final AccountIdDeriver ACCOUNT_ID_DERIVER =
      new AccountIdDeriver(UUID.randomUUID());
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-03-02T00:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate DATE = LocalDate.of(2026, 3, 2);

  @Test
  void the_worked_example_strikes_to_the_last_digit_end_to_end() {
    FundId fundId = FundId.of(UUID.randomUUID());
    FakeFundDirectory funds =
        new FakeFundDirectory(fund(fundId, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN)));
    FakeLedgerPort ledger = new FakeLedgerPort();
    ledger.givenUnitsInIssue(fundId, Units.of(1_000 * Units.SCALE));
    NavStrikeService service =
        service(
            funds,
            new FakeReportedBalanceSource(fundId, DATE, Money.of(1_050_000, CurrencyCode.MXN)),
            ledger,
            new FakeFundNavRepository());

    FundNav struck = service.strike(fundId, DATE);

    assertThat(struck.nav()).isEqualTo(Nav.of(1_050_000_000L, CurrencyCode.MXN));
    assertThat(struck.source()).isEqualTo("MODEL_B");
    assertThat(struck.supersedesId()).isNull();
  }

  @Test
  void a_fund_before_its_first_allotment_strikes_at_its_opening_price() {
    FundId fundId = FundId.of(UUID.randomUUID());
    Nav openingNav = Nav.of(10 * Nav.SCALE, CurrencyCode.MXN);
    NavStrikeService service =
        service(
            new FakeFundDirectory(fund(fundId, openingNav)),
            new FakeReportedBalanceSource(fundId, DATE, Money.of(0, CurrencyCode.MXN)),
            new FakeLedgerPort(), // no UNITS_IN_ISSUE account provisioned
            new FakeFundNavRepository());

    FundNav struck = service.strike(fundId, DATE);

    assertThat(struck.nav()).isEqualTo(openingNav);
    assertThat(struck.unitsInIssue().isZero()).isTrue();
  }

  @Test
  void a_falling_reported_balance_produces_a_lower_nav_with_nothing_clamping_it() {
    FundId fundId = FundId.of(UUID.randomUUID());
    FakeFundDirectory funds =
        new FakeFundDirectory(fund(fundId, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN)));
    FakeLedgerPort ledger = new FakeLedgerPort();
    ledger.givenUnitsInIssue(fundId, Units.of(1_000 * Units.SCALE));
    FakeFundNavRepository repository = new FakeFundNavRepository();
    LocalDate risingDate = LocalDate.of(2026, 3, 2);
    LocalDate fallenDate = LocalDate.of(2026, 3, 3);

    FundNav risingDay =
        service(
                funds,
                new FakeReportedBalanceSource(
                    fundId, risingDate, Money.of(1_050_000, CurrencyCode.MXN)),
                ledger,
                repository)
            .strike(fundId, risingDate);
    FundNav fallenDay =
        service(
                funds,
                new FakeReportedBalanceSource(
                    fundId, fallenDate, Money.of(300_000, CurrencyCode.MXN)),
                ledger,
                repository)
            .strike(fundId, fallenDate);

    assertThat(fallenDay.nav().scaled()).isLessThan(risingDay.nav().scaled());
  }

  @Test
  void striking_the_same_fund_and_date_twice_is_idempotent_even_if_units_in_issue_then_changes() {
    FundId fundId = FundId.of(UUID.randomUUID());
    FakeFundDirectory funds =
        new FakeFundDirectory(fund(fundId, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN)));
    FakeLedgerPort ledger = new FakeLedgerPort();
    ledger.givenUnitsInIssue(fundId, Units.of(1_000 * Units.SCALE));
    FakeFundNavRepository repository = new FakeFundNavRepository();
    NavStrikeService service =
        service(
            funds,
            new FakeReportedBalanceSource(fundId, DATE, Money.of(1_050_000, CurrencyCode.MXN)),
            ledger,
            repository);

    FundNav first = service.strike(fundId, DATE);
    // Units in issue moved between the two calls (an allotment ran), yet the second strike
    // for the same date must still return the first result unchanged.
    ledger.givenUnitsInIssue(fundId, Units.of(2_000 * Units.SCALE));
    FundNav second = service.strike(fundId, DATE);

    assertThat(second).isEqualTo(first);
    assertThat(repository.rowCountFor(fundId, DATE)).isEqualTo(1);
  }

  @Test
  void an_unknown_fund_is_rejected() {
    FundId unknownFundId = FundId.of(UUID.randomUUID());
    NavStrikeService service =
        service(
            new FakeFundDirectory(),
            new FakeReportedBalanceSource(unknownFundId, DATE, Money.of(0, CurrencyCode.MXN)),
            new FakeLedgerPort(),
            new FakeFundNavRepository());

    assertThatThrownBy(() -> service.strike(unknownFundId, DATE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_date_with_no_accepted_valuation_is_refused() {
    FundId fundId = FundId.of(UUID.randomUUID());
    NavStrikeService service =
        service(
            new FakeFundDirectory(fund(fundId, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN))),
            new FakeReportedBalanceSource(), // nothing accepted for any fund or date
            new FakeLedgerPort(),
            new FakeFundNavRepository());

    assertThatThrownBy(() -> service.strike(fundId, DATE))
        .isInstanceOf(NoSuchElementException.class);
  }

  private static NavStrikeService service(
      FundDirectory funds,
      ReportedBalanceSource reportedBalances,
      FakeLedgerPort ledger,
      FakeFundNavRepository repository) {
    return new NavStrikeService(
        funds,
        reportedBalances,
        ledger,
        ACCOUNT_ID_DERIVER,
        repository,
        new InMemoryNavCache(repository, NavSnapshotStore.NONE, CLOCK, Duration.ofHours(26)),
        CLOCK);
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
  private static final class FakeFundDirectory implements FundDirectory {
    private final Map<FundId, Fund> funds = new HashMap<>();

    FakeFundDirectory(Fund... seeded) {
      for (Fund fund : seeded) {
        funds.put(fund.fundId(), fund);
      }
    }

    @Override
    public Optional<Fund> resolveAsOf(FundId fundId, LocalDate asOfDate) {
      return Optional.ofNullable(funds.get(fundId));
    }

    @Override
    public List<Fund> activeAsOf(LocalDate asOfDate) {
      throw new UnsupportedOperationException("not used by this path");
    }
  }

  /** In-memory only — stands in for Task 20.3's accepted valuations. */
  private static final class FakeReportedBalanceSource implements ReportedBalanceSource {
    private final Map<FundId, Map<LocalDate, Money>> values = new HashMap<>();

    FakeReportedBalanceSource() {}

    FakeReportedBalanceSource(FundId fundId, LocalDate date, Money value) {
      values.computeIfAbsent(fundId, k -> new HashMap<>()).put(date, value);
    }

    @Override
    public Money reportedValueFor(FundId fund, LocalDate date) {
      Money value = values.getOrDefault(fund, Map.of()).get(date);
      if (value == null) {
        throw new NoSuchElementException(
            "No valuation has been accepted for fund " + fund + " on " + date);
      }
      return value;
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
    public AccountId createAccount(dev.fondaria.ledger.CreateAccountCommand cmd) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public List<LedgerAccount> lookupAccounts(List<AccountId> ids) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public dev.fondaria.ledger.TransferResult createTransfer(
        dev.fondaria.ledger.CreateTransferCommand cmd) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public List<dev.fondaria.ledger.TransferResult> createTransfers(
        List<dev.fondaria.ledger.CreateTransferCommand> cmds) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public List<dev.fondaria.ledger.TransferResult> createLinkedChain(
        List<dev.fondaria.ledger.CreateTransferCommand> cmds) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public Optional<dev.fondaria.ledger.LedgerTransfer> lookupTransfer(
        dev.fondaria.ledger.TransferId id) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public List<dev.fondaria.ledger.LedgerTransfer> accountTransfers(
        AccountId id, dev.fondaria.ledger.TransferQuery query) {
      throw new UnsupportedOperationException("not used by this path");
    }
  }

  /**
   * In-memory only. Mirrors the real {@code JdbcFundNavRepository}'s idempotency: the first strike
   * for a {@code (fundId, valuationDate)} wins, and a later candidate for the same pair is
   * discarded rather than overwriting it.
   */
  private static final class FakeFundNavRepository implements dev.fondaria.rules.FundNavRepository {
    private final Map<FundId, Map<LocalDate, FundNav>> originals = new HashMap<>();
    private long nextId = 1;

    @Override
    public Optional<FundNav> findOriginal(FundId fundId, LocalDate valuationDate) {
      return Optional.ofNullable(originals.getOrDefault(fundId, Map.of()).get(valuationDate));
    }

    /**
     * No correction can be stored here — {@link #strike} is the only writer this path exercises —
     * so every original is also live.
     */
    @Override
    public Optional<FundNav> findLatestLive(FundId fundId) {
      return originals.getOrDefault(fundId, Map.of()).values().stream()
          .max(Comparator.comparing(FundNav::valuationDate));
    }

    @Override
    public Optional<FundNav> findLive(FundId fundId, LocalDate valuationDate) {
      return Optional.ofNullable(originals.getOrDefault(fundId, Map.of()).get(valuationDate));
    }

    @Override
    public FundNav correct(FundNav candidate) {
      throw new UnsupportedOperationException(
          "not used by this path — see NavCorrectionServiceTest");
    }

    @Override
    public List<FundNav> findLatestLivePerFund() {
      return originals.keySet().stream()
          .map(this::findLatestLive)
          .flatMap(Optional::stream)
          .toList();
    }

    @Override
    public FundNav strike(FundNav candidate) {
      Map<LocalDate, FundNav> byDate =
          originals.computeIfAbsent(candidate.fundId(), k -> new HashMap<>());
      FundNav existing = byDate.get(candidate.valuationDate());
      if (existing != null) {
        return existing;
      }
      FundNav persisted =
          new FundNav(
              nextId++,
              candidate.fundId(),
              candidate.valuationDate(),
              candidate.nav(),
              candidate.unitsInIssue(),
              candidate.source(),
              candidate.supersedesId(),
              candidate.reason(),
              candidate.publishedAt());
      byDate.put(candidate.valuationDate(), persisted);
      return persisted;
    }

    int rowCountFor(FundId fundId, LocalDate valuationDate) {
      return originals.getOrDefault(fundId, Map.of()).containsKey(valuationDate) ? 1 : 0;
    }
  }
}

package dev.fondaria.balanceapi.asof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.balanceapi.balance.ParticipantNotFoundException;
import dev.fondaria.balanceapi.fund.FundDirectory;
import dev.fondaria.balanceapi.fund.RegisteredFund;
import dev.fondaria.balanceapi.participant.ParticipantDirectory;
import dev.fondaria.balanceapi.participant.ParticipantScope;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Nav;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.TenantRefDeriver;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a historical balance <i>is</i>, without a database: which holding applies on a date, how a
 * gap is read across, when the answer is a 404 rather than a zero, and which price is used.
 *
 * <p>The scale criterion is in {@code AsOfBalanceIntegrationTest}, against real rows and a real
 * index.
 */
class AsOfBalanceServiceTest {

  private static final UUID LEDGER_NAMESPACE = UUID.randomUUID();
  private static final OrganizationId ORGANIZATION = new OrganizationId(UUID.randomUUID());
  private static final String INTERNAL_REF = "EMP-000123";
  private static final ParticipantId PARTICIPANT = ParticipantId.of(UUID.randomUUID());
  private static final FundId FUND = FundId.of(UUID.randomUUID());
  private static final FundId OTHER_FUND = FundId.of(UUID.randomUUID());
  private static final long UNIT_LEDGER_ID = 1001L;
  private static final long OTHER_UNIT_LEDGER_ID = 1002L;

  private static final LocalDate MARCH_2 = LocalDate.of(2026, 3, 2);
  private static final LocalDate MARCH_10 = LocalDate.of(2026, 3, 10);
  private static final LocalDate MARCH_20 = LocalDate.of(2026, 3, 20);

  /** 1,000 units at 1e-8, and $12.50 per unit at 1e-8. */
  private static final long ONE_THOUSAND_UNITS = 1_000_00000000L;

  private static final long NAV_TWELVE_FIFTY = 12_50000000L;

  private final AccountIdDeriver accountIds = new AccountIdDeriver(LEDGER_NAMESPACE);
  private final TenantRefDeriver tenantRefs = new TenantRefDeriver(LEDGER_NAMESPACE);

  private final String vested = holding(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, FUND);
  private final String unvested =
      holding(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED, FUND);
  private final String otherVested =
      holding(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, OTHER_FUND);

  // -------------------------------------------------------------------------------------------
  // "Correct value on an activity date and on a gap date."
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a date the holding changed on is valued at that day's NAV")
  void anActivityDateIsValuedAtThatDaysNav() {
    FakeHistoricUnitsRepository holdings =
        new FakeHistoricUnitsRepository().holding(vested, MARCH_10, ONE_THOUSAND_UNITS);
    FakeFundNavRepository navs =
        new FakeFundNavRepository().struck(FUND, MARCH_10, NAV_TWELVE_FIFTY, 77L);

    AsOfBalance balance = service(holdings, navs).balanceAsOf(PARTICIPANT, MARCH_10);

    assertThat(balance.date()).isEqualTo(MARCH_10);
    assertThat(balance.positions()).hasSize(1);
    assertThat(balance.positions().get(0).units()).isEqualTo(Units.of(ONE_THOUSAND_UNITS));
    assertThat(balance.positions().get(0).nav().navVersionId()).isEqualTo(77L);
    assertThat(balance.value())
        .isEqualTo(Money.value(Units.of(ONE_THOUSAND_UNITS), Nav.of(NAV_TWELVE_FIFTY, MXN())));
  }

  @Test
  @DisplayName("a gap date returns the last holding before it, at the gap date's own NAV")
  void aGapDateReturnsTheLastHoldingAtThatDaysNav() {
    FakeHistoricUnitsRepository holdings =
        new FakeHistoricUnitsRepository()
            .holding(vested, MARCH_2, ONE_THOUSAND_UNITS)
            // A later change exists, and must not be the one applied to a date before it.
            .holding(vested, MARCH_20, 9_999_00000000L);
    FakeFundNavRepository navs =
        new FakeFundNavRepository()
            .struck(FUND, MARCH_10, NAV_TWELVE_FIFTY, 88L)
            .struck(FUND, MARCH_20, 99_00000000L, 99L);

    AsOfBalance balance = service(holdings, navs).balanceAsOf(PARTICIPANT, MARCH_10);

    assertThat(balance.positions()).hasSize(1);
    assertThat(balance.positions().get(0).units())
        .as("the holding carried across the gap, not the later one")
        .isEqualTo(Units.of(ONE_THOUSAND_UNITS));
    assertThat(balance.positions().get(0).nav().valuationDate())
        .as("priced on the date asked for, not on the date the holding changed")
        .isEqualTo(MARCH_10);
  }

  @Test
  @DisplayName("vested and unvested are summed into one figure per fund, rounded once")
  void vestedAndUnvestedAreSummedPerFund() {
    // Chosen so per-lot rounding and combined rounding genuinely differ. Each lot is 100.005 units
    // at 1.00, exactly 10,000.5 centavos: rounded half-up per lot and added that is 20,002, while
    // the combined 200.01 units is exactly 20,001. The as-of contract renders one units figure per
    // fund, so a reader who repeats the multiplication must land on 20,001 — which is what
    // ADR-0013's clause for this task means by "round per fund".
    long lotUnits = 100_00500000L;
    long nav = 1_00000000L;
    FakeHistoricUnitsRepository holdings =
        new FakeHistoricUnitsRepository()
            .holding(vested, MARCH_2, lotUnits)
            .holding(unvested, MARCH_2, lotUnits);
    FakeFundNavRepository navs = new FakeFundNavRepository().struck(FUND, MARCH_10, nav, 12L);

    AsOfBalance balance = service(holdings, navs).balanceAsOf(PARTICIPANT, MARCH_10);

    AsOfPosition position = balance.positions().get(0);
    assertThat(position.units()).isEqualTo(Units.of(2 * lotUnits));
    assertThat(position.value().cents()).isEqualTo(20_001L);
    assertThat(Money.value(Units.of(lotUnits), Nav.of(nav, MXN())).cents() * 2)
        .as(
            "the per-lot form is one centavo away — which is what GET /balance renders for this"
                + " same holding, a known and accepted divergence (ADR-0014)")
        .isEqualTo(20_002L);
    assertThat(balance.value())
        .as("the headline is the exact sum of the rendered positions")
        .isEqualTo(position.value());
  }

  @Test
  @DisplayName("each account's own latest row applies — the two lots change on different dates")
  void eachLotIsResolvedIndependently() {
    FakeHistoricUnitsRepository holdings =
        new FakeHistoricUnitsRepository()
            .holding(vested, MARCH_2, ONE_THOUSAND_UNITS)
            .holding(unvested, MARCH_20, 500_00000000L);
    FakeFundNavRepository navs =
        new FakeFundNavRepository().struck(FUND, MARCH_10, NAV_TWELVE_FIFTY, 5L);

    AsOfBalance balance = service(holdings, navs).balanceAsOf(PARTICIPANT, MARCH_10);

    assertThat(balance.positions().get(0).units())
        .as("the unvested lot did not exist yet on the tenth")
        .isEqualTo(Units.of(ONE_THOUSAND_UNITS));
  }

  @Test
  @DisplayName("a fund with no holding on the date is absent, not a zero position")
  void aFundHeldInLaterIsAbsent() {
    FakeHistoricUnitsRepository holdings =
        new FakeHistoricUnitsRepository()
            .holding(vested, MARCH_2, ONE_THOUSAND_UNITS)
            .holding(otherVested, MARCH_20, ONE_THOUSAND_UNITS);
    FakeFundNavRepository navs =
        new FakeFundNavRepository().struck(FUND, MARCH_10, NAV_TWELVE_FIFTY, 5L);

    AsOfBalance balance = service(holdings, navs).balanceAsOf(PARTICIPANT, MARCH_10);

    assertThat(balance.positions()).hasSize(1);
    assertThat(balance.positions().get(0).fundId()).isEqualTo(FUND);
  }

  // -------------------------------------------------------------------------------------------
  // "Date before first contribution → 404."
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a date before the first contribution is not found — never a zero balance")
  void aDateBeforeTheFirstContributionIsNotFound() {
    FakeHistoricUnitsRepository holdings =
        new FakeHistoricUnitsRepository().holding(vested, MARCH_10, ONE_THOUSAND_UNITS);
    FakeFundNavRepository navs =
        new FakeFundNavRepository().struck(FUND, MARCH_2, NAV_TWELVE_FIFTY, 5L);

    assertThatThrownBy(() -> service(holdings, navs).balanceAsOf(PARTICIPANT, MARCH_2))
        .isInstanceOf(NoHoldingOnRecordException.class);
  }

  @Test
  @DisplayName("a participant who has never held anything is not found, at any date")
  void aParticipantWithNoHoldingAtAllIsNotFound() {
    assertThatThrownBy(
            () ->
                service(new FakeHistoricUnitsRepository(), new FakeFundNavRepository())
                    .balanceAsOf(PARTICIPANT, MARCH_10))
        .isInstanceOf(NoHoldingOnRecordException.class);
  }

  @Test
  @DisplayName("a holding redeemed to zero is a zero balance, not a 404 — a different claim")
  void aFullyRedeemedHoldingIsZeroAndNotFourOhFour() {
    FakeHistoricUnitsRepository holdings =
        new FakeHistoricUnitsRepository()
            .holding(vested, MARCH_2, ONE_THOUSAND_UNITS)
            .holding(vested, MARCH_10, 0L);
    // No price is registered for the tenth at all: a fund holding nothing must not need one.
    FakeFundNavRepository navs = new FakeFundNavRepository();

    AsOfBalance balance = service(holdings, navs).balanceAsOf(PARTICIPANT, MARCH_10);

    assertThat(balance.positions()).isEmpty();
    assertThat(balance.value().cents()).isZero();
  }

  @Test
  @DisplayName("an empty fund registry is unavailable, not a 404 about the participant")
  void anEmptyFundRegistryIsUnavailable() {
    AsOfBalanceService service =
        new AsOfBalanceService(
            resolving(),
            List::of,
            new FakeHistoricUnitsRepository(),
            new FakeFundNavRepository(),
            accountIds,
            tenantRefs);

    assertThatThrownBy(() -> service.balanceAsOf(PARTICIPANT, MARCH_10))
        .as("an empty registry is a platform fault, never a claim about somebody's records")
        .isInstanceOf(FundRegistryUnavailableException.class);
  }

  @Test
  @DisplayName("an unknown participant is not found before any projection read happens")
  void anUnknownParticipantIsNotFound() {
    AsOfBalanceService service =
        new AsOfBalanceService(
            participantId -> Optional.empty(),
            registry(),
            new FakeHistoricUnitsRepository(),
            new FakeFundNavRepository(),
            accountIds,
            tenantRefs);

    assertThatThrownBy(() -> service.balanceAsOf(PARTICIPANT, MARCH_10))
        .isInstanceOf(ParticipantNotFoundException.class);
  }

  // -------------------------------------------------------------------------------------------
  // "A superseded NAV date returns the corrected value and flags the correction."
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a corrected NAV date is valued at the correction and says so")
  void aCorrectedNavIsServedAndFlagged() {
    FakeHistoricUnitsRepository holdings =
        new FakeHistoricUnitsRepository().holding(vested, MARCH_2, ONE_THOUSAND_UNITS);
    // Version 41 superseded version 40. The fake refuses findOriginal outright, so a service
    // reaching for the pre-correction price fails here rather than serving a wrong number.
    FakeFundNavRepository navs =
        new FakeFundNavRepository().corrected(FUND, MARCH_10, 9_00000000L, 41L, 40L);

    AsOfBalance balance = service(holdings, navs).balanceAsOf(PARTICIPANT, MARCH_10);

    AsOfPosition position = balance.positions().get(0);
    assertThat(position.corrected()).isTrue();
    assertThat(position.nav().navVersionId()).isEqualTo(41L);
    assertThat(position.value())
        .isEqualTo(Money.value(Units.of(ONE_THOUSAND_UNITS), Nav.of(9_00000000L, MXN())));
  }

  @Test
  @DisplayName("an uncorrected NAV is not flagged as corrected")
  void anUncorrectedNavIsNotFlagged() {
    FakeHistoricUnitsRepository holdings =
        new FakeHistoricUnitsRepository().holding(vested, MARCH_2, ONE_THOUSAND_UNITS);
    FakeFundNavRepository navs =
        new FakeFundNavRepository().struck(FUND, MARCH_10, NAV_TWELVE_FIFTY, 41L);

    AsOfBalance balance = service(holdings, navs).balanceAsOf(PARTICIPANT, MARCH_10);

    assertThat(balance.positions().get(0).corrected()).isFalse();
  }

  // -------------------------------------------------------------------------------------------
  // Failure modes that must not become a smaller number.
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a held fund with no price for the date fails the whole request, not just its own")
  void aHeldFundWithoutAPriceFailsTheRequest() {
    FakeHistoricUnitsRepository holdings =
        new FakeHistoricUnitsRepository()
            .holding(vested, MARCH_2, ONE_THOUSAND_UNITS)
            .holding(otherVested, MARCH_2, ONE_THOUSAND_UNITS);
    FakeFundNavRepository navs =
        new FakeFundNavRepository().struck(FUND, MARCH_10, NAV_TWELVE_FIFTY, 5L);

    assertThatThrownBy(() -> service(holdings, navs).balanceAsOf(PARTICIPANT, MARCH_10))
        .isInstanceOf(PriceNotOnRecordException.class)
        .extracting(e -> ((PriceNotOnRecordException) e).fundId())
        .isEqualTo(OTHER_FUND);
  }

  @Test
  @DisplayName("a falling NAV produces a smaller value, never a clamped one")
  void aFallingNavProducesASmallerValue() {
    FakeHistoricUnitsRepository holdings =
        new FakeHistoricUnitsRepository().holding(vested, MARCH_2, ONE_THOUSAND_UNITS);
    FakeFundNavRepository navs =
        new FakeFundNavRepository()
            .struck(FUND, MARCH_2, 10_00000000L, 1L)
            .struck(FUND, MARCH_10, 7_25000000L, 2L);

    AsOfBalanceService service = service(holdings, navs);

    assertThat(service.balanceAsOf(PARTICIPANT, MARCH_2).value().cents()).isEqualTo(1_000_000L);
    assertThat(service.balanceAsOf(PARTICIPANT, MARCH_10).value().cents()).isEqualTo(725_000L);
  }

  @Test
  @DisplayName(
      "both holding accounts of every registered fund are asked for, and each exactly once")
  void everyHoldingAccountIsAskedForExactlyOnce() {
    FakeHistoricUnitsRepository holdings =
        new FakeHistoricUnitsRepository().holding(vested, MARCH_2, ONE_THOUSAND_UNITS);
    FakeFundNavRepository navs =
        new FakeFundNavRepository().struck(FUND, MARCH_10, NAV_TWELVE_FIFTY, 5L);

    service(holdings, navs).balanceAsOf(PARTICIPANT, MARCH_10);

    assertThat(holdings.accountsAsked())
        .as("two accounts per registered fund, and nothing that scales with participants")
        .hasSize(4)
        .containsExactlyInAnyOrder(
            vested,
            unvested,
            otherVested,
            holding(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED, OTHER_FUND));
  }

  // -------------------------------------------------------------------------------------------

  private AsOfBalanceService service(
      FakeHistoricUnitsRepository holdings, FakeFundNavRepository navs) {
    return new AsOfBalanceService(resolving(), registry(), holdings, navs, accountIds, tenantRefs);
  }

  private static FundDirectory registry() {
    return () ->
        List.of(
            new RegisteredFund(FUND, UNIT_LEDGER_ID),
            new RegisteredFund(OTHER_FUND, OTHER_UNIT_LEDGER_ID));
  }

  private static ParticipantDirectory resolving() {
    return participantId -> Optional.of(new ParticipantScope(ORGANIZATION, INTERNAL_REF));
  }

  private String holding(LedgerCodes.AccountCode code, FundId fundId) {
    return accountIds
        .derive(code, ORGANIZATION.scope() + ":" + INTERNAL_REF + ":" + fundId)
        .asUnsignedDecimal();
  }

  private static dev.fondaria.common.CurrencyCode MXN() {
    return dev.fondaria.common.CurrencyCode.MXN;
  }
}

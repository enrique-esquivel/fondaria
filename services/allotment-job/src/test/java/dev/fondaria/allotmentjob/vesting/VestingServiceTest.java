package dev.fondaria.allotmentjob.vesting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.allotmentjob.allotment.AllotmentMetrics;
import dev.fondaria.allotmentjob.participant.VestingParticipant;
import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerRejectionReason;
import dev.fondaria.ledger.TenantRefDeriver;
import dev.fondaria.ledger.TransferIdDeriver;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundStatus;
import dev.fondaria.rules.PlanVersion;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@link VestingService} against fakes — no database, no ledger. {@code
 * VestingServiceIntegrationTest} proves the same logic against a real TigerBeetle and PostgreSQL.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class VestingServiceTest {

  private static final UUID LEDGER_NAMESPACE = UUID.randomUUID();
  // A Wednesday, so BusinessCalendar's weekend roll-forward never moves it.
  private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
  private static final LocalDate ASOF = LocalDate.of(2026, 8, 26);
  private static final String PLAN_ID = "PLAN-ACME-RETIREMENT";
  private static final long FUND_LEDGER_ID = 5001L;
  private static final String PARTICIPANT_REF = "P-001";

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final FundId fundId = FundId.of(UUID.randomUUID());
  private final BusinessCalendar calendar =
      new BusinessCalendar(ZoneOffset.UTC, LocalTime.of(18, 0), Set.of());
  private final AccountIdDeriver accountIds = new AccountIdDeriver(LEDGER_NAMESPACE);
  private final TransferIdDeriver transferIds = new TransferIdDeriver(LEDGER_NAMESPACE);
  private final TenantRefDeriver tenantRefs = new TenantRefDeriver(LEDGER_NAMESPACE);
  private final FakeVestingLedgerPort ledger = new FakeVestingLedgerPort();
  private final InMemoryVestingDirectories directories = new InMemoryVestingDirectories();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final VestingService service =
      new VestingService(
          directories,
          directories,
          directories,
          calendar,
          ledger,
          accountIds,
          transferIds,
          tenantRefs,
          clock,
          new AllotmentMetrics(meterRegistry));

  @BeforeEach
  void publishFund() {
    directories.givenFund(
        new Fund(
            fundId,
            LocalDate.of(2015, 1, 1),
            1,
            "Fondo de Retiro Acme",
            "RETIREMENT",
            CurrencyCode.MXN,
            FUND_LEDGER_ID,
            Units.SCALE,
            LocalDate.of(2015, 1, 1),
            Nav.of(10 * Units.SCALE, CurrencyCode.MXN),
            FundStatus.ACTIVE,
            Instant.parse("2015-01-01T00:00:00Z")));
  }

  private PlanVersion planVersion(int vestingFullYears) {
    return new PlanVersion(
        organizationId,
        PLAN_ID,
        LocalDate.of(2010, 1, 1),
        1,
        1,
        2,
        vestingFullYears,
        0,
        fundId,
        Instant.parse("2010-01-01T00:00:00Z"));
  }

  private String scope(String participantRef) {
    return organizationId.scope() + ":" + participantRef + ":" + fundId;
  }

  private AccountId unvestedAccount(String participantRef) {
    return accountIds.derive(
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED, scope(participantRef));
  }

  private AccountId vestedAccount(String participantRef) {
    return accountIds.derive(
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, scope(participantRef));
  }

  private VestingCandidate candidate(String participantRef, long basisScaled) {
    return new VestingCandidate(organizationId, participantRef, fundId, Units.of(basisScaled));
  }

  @Test
  void a_participant_past_the_vesting_horizon_has_their_whole_unvested_balance_moved() {
    directories.givenPlanVersion(planVersion(5));
    // Two full schedules in the past -- VestingScheduleCalculator clamps to 10000 bps
    // regardless of calendar day-count arithmetic, so this is exact by construction.
    directories.givenParticipant(
        organizationId, PARTICIPANT_REF, new VestingParticipant(PLAN_ID, ASOF.minusYears(20)));
    long basisScaled = 150 * Units.SCALE;
    ledger.givenUnvestedBalance(unvestedAccount(PARTICIPANT_REF), Units.of(basisScaled));

    Optional<Units> moved = service.vest(candidate(PARTICIPANT_REF, basisScaled));

    assertThat(moved).contains(Units.of(basisScaled));
    assertThat(ledger.balanceOf(unvestedAccount(PARTICIPANT_REF))).isEqualTo(Units.of(0));
    assertThat(ledger.balanceOf(vestedAccount(PARTICIPANT_REF))).isEqualTo(Units.of(basisScaled));
    // The VESTING movement is attributed to the participant's organization (ADR-0005).
    assertThat(ledger.transfersCreated())
        .allSatisfy(cmd -> assertThat(cmd.tenant()).isEqualTo(tenantRefs.derive(organizationId)));
    // Total units held by the participant in this fund is unchanged by the move.
    long totalAfter =
        ledger.balanceOf(unvestedAccount(PARTICIPANT_REF)).scaled()
            + ledger.balanceOf(vestedAccount(PARTICIPANT_REF)).scaled();
    assertThat(totalAfter).isEqualTo(basisScaled);
    assertThat(meterRegistry.get("transfer_created_total").tag("code", "VESTING").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void rerunning_the_job_the_same_day_moves_nothing() {
    directories.givenPlanVersion(planVersion(5));
    directories.givenParticipant(
        organizationId, PARTICIPANT_REF, new VestingParticipant(PLAN_ID, ASOF.minusYears(20)));
    long basisScaled = 150 * Units.SCALE;
    ledger.givenUnvestedBalance(unvestedAccount(PARTICIPANT_REF), Units.of(basisScaled));

    Optional<Units> first = service.vest(candidate(PARTICIPANT_REF, basisScaled));
    Optional<Units> second = service.vest(candidate(PARTICIPANT_REF, basisScaled));

    assertThat(first).contains(Units.of(basisScaled));
    assertThat(second).contains(Units.of(0));
    assertThat(ledger.transfersCreated()).hasSize(1);
  }

  @Test
  void vesting_computed_from_employment_start_date_differs_between_two_participants() {
    directories.givenPlanVersion(planVersion(5));
    long basisScaled = 100 * Units.SCALE;
    // Same schedule, same basis, different seniority: the participant who started earlier
    // must have more of their employer lot vested today.
    directories.givenParticipant(
        organizationId, "P-SENIOR", new VestingParticipant(PLAN_ID, ASOF.minusYears(4)));
    directories.givenParticipant(
        organizationId, "P-JUNIOR", new VestingParticipant(PLAN_ID, ASOF.minusMonths(6)));
    ledger.givenUnvestedBalance(unvestedAccount("P-SENIOR"), Units.of(basisScaled));
    ledger.givenUnvestedBalance(unvestedAccount("P-JUNIOR"), Units.of(basisScaled));

    Optional<Units> seniorMoved = service.vest(candidate("P-SENIOR", basisScaled));
    Optional<Units> juniorMoved = service.vest(candidate("P-JUNIOR", basisScaled));

    assertThat(seniorMoved).isPresent();
    assertThat(juniorMoved).isPresent();
    assertThat(seniorMoved.get().scaled()).isGreaterThan(juniorMoved.get().scaled());
  }

  @Test
  void not_ready_when_the_participant_does_not_resolve() {
    directories.givenPlanVersion(planVersion(5));
    ledger.givenUnvestedBalance(unvestedAccount(PARTICIPANT_REF), Units.of(100 * Units.SCALE));

    Optional<Units> result = service.vest(candidate(PARTICIPANT_REF, 100 * Units.SCALE));

    assertThat(result).isEmpty();
    assertThat(ledger.transfersCreated()).isEmpty();
  }

  @Test
  void not_ready_when_the_plan_version_does_not_resolve() {
    directories.givenParticipant(
        organizationId, PARTICIPANT_REF, new VestingParticipant(PLAN_ID, ASOF.minusYears(20)));
    ledger.givenUnvestedBalance(unvestedAccount(PARTICIPANT_REF), Units.of(100 * Units.SCALE));

    Optional<Units> result = service.vest(candidate(PARTICIPANT_REF, 100 * Units.SCALE));

    assertThat(result).isEmpty();
    assertThat(ledger.transfersCreated()).isEmpty();
  }

  @Test
  void not_ready_when_the_fund_does_not_resolve() {
    FundId unregisteredFund = FundId.of(UUID.randomUUID());
    directories.givenPlanVersion(planVersion(5));
    directories.givenParticipant(
        organizationId, PARTICIPANT_REF, new VestingParticipant(PLAN_ID, ASOF.minusYears(20)));
    VestingCandidate candidate =
        new VestingCandidate(
            organizationId, PARTICIPANT_REF, unregisteredFund, Units.of(100 * Units.SCALE));

    Optional<Units> result = service.vest(candidate);

    assertThat(result).isEmpty();
  }

  @Test
  void not_ready_when_the_unvested_account_has_not_been_opened_yet() {
    directories.givenPlanVersion(planVersion(5));
    directories.givenParticipant(
        organizationId, PARTICIPANT_REF, new VestingParticipant(PLAN_ID, ASOF.minusYears(20)));

    Optional<Units> result = service.vest(candidate(PARTICIPANT_REF, 100 * Units.SCALE));

    assertThat(result).isEmpty();
    assertThat(ledger.transfersCreated()).isEmpty();
  }

  @Test
  void a_ledger_rejection_is_a_genuine_failure() {
    directories.givenPlanVersion(planVersion(5));
    directories.givenParticipant(
        organizationId, PARTICIPANT_REF, new VestingParticipant(PLAN_ID, ASOF.minusYears(20)));
    long basisScaled = 150 * Units.SCALE;
    ledger.givenUnvestedBalance(unvestedAccount(PARTICIPANT_REF), Units.of(basisScaled));
    ledger.rejectNextTransferWith(LedgerRejectionReason.ACCOUNT_NOT_FOUND);

    assertThatThrownBy(() -> service.vest(candidate(PARTICIPANT_REF, basisScaled)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            meterRegistry
                .get("transfer_rejected_total")
                .tag("reason", "ACCOUNT_NOT_FOUND")
                .counter()
                .count())
        .isEqualTo(1.0);
  }
}

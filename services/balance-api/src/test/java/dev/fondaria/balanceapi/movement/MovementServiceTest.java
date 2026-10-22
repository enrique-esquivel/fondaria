package dev.fondaria.balanceapi.movement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.balanceapi.balance.ParticipantNotFoundException;
import dev.fondaria.balanceapi.fund.FundDirectory;
import dev.fondaria.balanceapi.fund.RegisteredFund;
import dev.fondaria.balanceapi.participant.ParticipantDirectory;
import dev.fondaria.balanceapi.participant.ParticipantScope;
import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.TenantRefDeriver;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a page <i>is</i>, without a database: which rows are on it, in what order, where the cursor
 * lands, and what happens at the boundaries.
 *
 * <p>The scale criteria are in {@code MovementHistoryIntegrationTest}, against real rows.
 */
class MovementServiceTest {

  private static final UUID LEDGER_NAMESPACE = UUID.randomUUID();
  private static final OrganizationId ORGANIZATION = new OrganizationId(UUID.randomUUID());
  private static final String INTERNAL_REF = "EMP-000123";
  private static final ParticipantId PARTICIPANT = ParticipantId.of(UUID.randomUUID());
  private static final FundId FUND = FundId.of(UUID.randomUUID());

  /** Any unit ledger id; this endpoint never uses it — the projection's fund key does. */
  private static final long UNIT_LEDGER_ID = 1001L;

  private final AccountIdDeriver accountIds = new AccountIdDeriver(LEDGER_NAMESPACE);
  private final TenantRefDeriver tenantRefs = new TenantRefDeriver(LEDGER_NAMESPACE);
  private final CursorCodec cursors = new CursorCodec("a-test-signing-key-of-adequate-length");

  private final String vested =
      accountIds
          .derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, scope())
          .asUnsignedDecimal();
  private final String unvested =
      accountIds
          .derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED, scope())
          .asUnsignedDecimal();

  private static String scope() {
    return ORGANIZATION.scope() + ":" + INTERNAL_REF + ":" + FUND;
  }

  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("an unknown participant is not found — never an empty history, a different claim")
  void anUnknownParticipantIsNotFound() {
    MovementService service =
        service(new FakeMovementRepository(), participantId -> Optional.empty());

    assertThatThrownBy(() -> service.movements(query(50, Optional.empty())))
        .isInstanceOf(ParticipantNotFoundException.class);
  }

  @Test
  @DisplayName("movements come back newest first")
  void newestFirst() {
    FakeMovementRepository repository = new FakeMovementRepository();
    repository
        .with(allotment(100, vested))
        .with(allotment(300, vested))
        .with(allotment(200, vested));

    MovementPage page = service(repository).movements(query(50, Optional.empty()));

    assertThat(page.items())
        .extracting(Movement::ledgerTimestamp)
        .containsExactly("300", "200", "100");
    assertThat(page.hasMore()).isFalse();
    assertThat(page.nextCursor()).isEmpty();
  }

  @Test
  @DisplayName("paging returns every movement exactly once, and stops")
  void pagingCoversEveryMovementOnce() {
    FakeMovementRepository repository = new FakeMovementRepository();
    for (int i = 1; i <= 25; i++) {
      repository.with(allotment(i, vested));
    }

    List<String> seen = pageThrough(service(repository), 4);

    assertThat(seen).hasSize(25).doesNotHaveDuplicates();
  }

  /**
   * The reason {@link MovementCursor} carries a leg. A vesting transfer debits the unvested account
   * and credits the vested one: one {@code projection.transfer} row, two movements, one timestamp
   * and one transfer id between them.
   */
  @Test
  @DisplayName(
      "both legs of a vesting transfer are reported, and page boundaries fall between them")
  void aVestingTransferIsTwoMovements() {
    FakeMovementRepository repository = new FakeMovementRepository();
    repository
        .with(leg("500", unvested, MovementDirection.DEBIT, LedgerCodes.TransferCode.VESTING))
        .with(leg("500", vested, MovementDirection.CREDIT, LedgerCodes.TransferCode.VESTING));

    MovementPage both = service(repository).movements(query(50, Optional.empty()));
    assertThat(both.items())
        .extracting(Movement::holdingAccount, Movement::direction)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(HoldingAccount.UNVESTED, MovementDirection.DEBIT),
            org.assertj.core.groups.Tuple.tuple(HoldingAccount.VESTED, MovementDirection.CREDIT));

    // And a page that ends between the two legs continues onto the second, not back onto the first.
    MovementPage first = service(repository).movements(query(1, Optional.empty()));
    assertThat(first.hasMore()).isTrue();
    MovementPage second = service(repository).movements(query(1, first.nextCursor()));
    assertThat(second.items())
        .singleElement()
        .satisfies(
            movement -> {
              assertThat(movement.holdingAccount()).isEqualTo(HoldingAccount.VESTED);
              assertThat(movement.direction()).isEqualTo(MovementDirection.CREDIT);
            });
    assertThat(second.hasMore()).isFalse();
  }

  @Test
  @DisplayName("a fund filter narrows the accounts asked for rather than filtering afterwards")
  void aFundFilterNarrowsTheAccounts() {
    FakeMovementRepository repository = new FakeMovementRepository();
    repository.with(allotment(100, vested));

    MovementQuery elsewhere =
        new MovementQuery(
            PARTICIPANT,
            Optional.empty(),
            Optional.empty(),
            Optional.of(FundId.of(UUID.randomUUID())),
            Optional.empty(),
            50,
            Optional.empty());

    assertThat(service(repository).movements(elsewhere).items()).isEmpty();
  }

  @Test
  @DisplayName("with no fund registered there is nothing to ask the database for")
  void noFundsMeansNoQuery() {
    FakeMovementRepository repository = new FakeMovementRepository();
    MovementService service =
        new MovementService(resolving(), List::of, repository, accountIds, tenantRefs, cursors);

    assertThat(service.movements(query(50, Optional.empty())).items()).isEmpty();
    assertThat(repository.pagesServed()).isZero();
  }

  @Test
  @DisplayName("a transfer code a holding account cannot carry fails loudly, never silently")
  void anImpossibleTransferCodeIsNotSwallowed() {
    FakeMovementRepository repository = new FakeMovementRepository();
    repository.with(
        new MovementLeg(
            "1",
            "100",
            vested,
            MovementDirection.CREDIT,
            1_000L,
            LedgerCodes.TransferCode.CASH_RECEIPT.code(),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 3),
            Optional.empty()));

    assertThatThrownBy(() -> service(repository).movements(query(50, Optional.empty())))
        .isInstanceOf(UnknownMovementTransferCodeException.class);
  }

  @Test
  @DisplayName("a cursor from another query is refused")
  void aForeignCursorIsRefused() {
    FakeMovementRepository repository = new FakeMovementRepository();
    repository.with(allotment(100, vested));

    assertThatThrownBy(() -> service(repository).movements(query(50, Optional.of("not.a-cursor"))))
        .isInstanceOf(TamperedCursorException.class);
  }

  // -------------------------------------------------------------------------------------------

  private List<String> pageThrough(MovementService service, int pageSize) {
    List<String> seen = new ArrayList<>();
    Optional<String> cursor = Optional.empty();
    while (true) {
      MovementPage page = service.movements(query(pageSize, cursor));
      page.items().forEach(movement -> seen.add(movement.ledgerTimestamp()));
      if (!page.hasMore()) {
        return seen;
      }
      cursor = page.nextCursor();
    }
  }

  private MovementQuery query(int limit, Optional<String> cursor) {
    return new MovementQuery(
        PARTICIPANT,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        limit,
        cursor);
  }

  private MovementService service(MovementRepository repository) {
    return service(repository, resolving());
  }

  private MovementService service(MovementRepository repository, ParticipantDirectory directory) {
    FundDirectory funds = () -> List.of(new RegisteredFund(FUND, UNIT_LEDGER_ID));
    return new MovementService(directory, funds, repository, accountIds, tenantRefs, cursors);
  }

  private static ParticipantDirectory resolving() {
    return participantId -> Optional.of(new ParticipantScope(ORGANIZATION, INTERNAL_REF));
  }

  private static MovementLeg allotment(int timestamp, String accountId) {
    return leg(
        Integer.toString(timestamp),
        accountId,
        MovementDirection.CREDIT,
        LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT);
  }

  private static MovementLeg leg(
      String timestamp,
      String accountId,
      MovementDirection direction,
      LedgerCodes.TransferCode code) {
    return new MovementLeg(
        timestamp,
        timestamp,
        accountId,
        direction,
        1_000_00000000L,
        code.code(),
        LocalDate.of(2026, 8, 3),
        LocalDate.of(2026, 8, 3),
        code == LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT
            ? Optional.of(4242L)
            : Optional.empty());
  }
}

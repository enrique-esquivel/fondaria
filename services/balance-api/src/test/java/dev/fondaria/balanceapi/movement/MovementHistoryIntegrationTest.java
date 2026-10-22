package dev.fondaria.balanceapi.movement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.balanceapi.fund.FundDirectory;
import dev.fondaria.balanceapi.participant.ParticipantScope;
import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import dev.fondaria.common.TenantRef;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.testing.PostgresFixture;
import dev.fondaria.testing.TigerBeetleFixture;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The movement history against a real PostgreSQL and the real partitioned {@code
 * projection.transfer} (Task 6.3).
 *
 * <p><b>Every test creates its own organization, fund, participant and tenant partition</b>, and
 * asserts only over the rows it inserted. Fixtures are reused across runs (AGENTS.md §4), so a
 * count over the whole table would pass or fail on what an earlier run left behind.
 *
 * <p><b>Cleanup drops the tenant's partitions rather than deleting rows.</b> {@code
 * projection.transfer} is append-only and carries a {@code BEFORE DELETE} trigger that raises for
 * every role, owner included (V21) — which is the point of it. DDL is not DML: dropping the
 * partition a fresh tenant id created removes exactly this test's rows and nobody else's, and it is
 * also the only way a test can leave the shared database as it found it.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MovementHistoryIntegrationTest {

  private static final UUID LEDGER_NAMESPACE = UUID.randomUUID();
  private static final String CURSOR_SECRET = "an-integration-test-cursor-signing-key";

  private static final int PAGE_SIZE = 200;
  private static final long UNITS_PER_MOVEMENT = 1_00000000L;

  @Autowired private MovementService movements;
  @Autowired private FundDirectory funds;
  @Autowired private JdbcClient jdbc;
  @Autowired private Clock clock;

  private final AccountIdDeriver accountIds = new AccountIdDeriver(LEDGER_NAMESPACE);
  private final List<String> tenantsCreated = new ArrayList<>();
  private final List<FundId> fundsRegistered = new ArrayList<>();

  @DynamicPropertySource
  static void serviceProperties(DynamicPropertyRegistry registry) {
    PostgresFixture postgres = PostgresFixture.getInstance();
    registry.add("fondaria.database.url", postgres::getJdbcUrl);
    registry.add("fondaria.database.username", postgres::getUsername);
    registry.add("fondaria.database.password", postgres::getPassword);

    // The context builds a ledger client at startup even though a history never reads the ledger.
    TigerBeetleFixture tigerBeetle = TigerBeetleFixture.getInstance();
    registry.add("fondaria.ledger.addresses", tigerBeetle::getAddresses);
    registry.add("fondaria.ledger.client.cluster-id", () -> 0L);
    registry.add("fondaria.identity.ledger-namespace", () -> LEDGER_NAMESPACE);
    registry.add("fondaria.query.cursor-secret", () -> CURSOR_SECRET);
    registry.add("fondaria.nav-cache.refresh-interval", () -> "1h");
  }

  /**
   * <b>Funds are removed as well as partitions.</b> The fixture database is reused across runs
   * (AGENTS.md §4), and the candidate fund set this endpoint fans out over is the whole of {@code
   * reference.fund_identity} — four query branches per fund. A suite that registered a fund per
   * test and never removed it would make its own heaviest test measurably slower on every
   * subsequent run and eventually time out: intermittent, environment-dependent, and looking like
   * an unrelated regression. So this suite leaves the registry as it found it.
   */
  @AfterEach
  void dropTenantPartitionsAndFunds() {
    for (FundId fundId : fundsRegistered) {
      jdbc.sql("DELETE FROM reference.fund WHERE fund_id = :f").param("f", fundId.value()).update();
      jdbc.sql("DELETE FROM reference.fund_identity WHERE fund_id = :f")
          .param("f", fundId.value())
          .update();
    }
    fundsRegistered.clear();
    for (String tenantId : tenantsCreated) {
      String slug =
          jdbc.sql("SELECT left(md5(:t), 16)").param("t", tenantId).query(String.class).single();
      jdbc.sql("DROP TABLE IF EXISTS projection.transfer_t_" + slug + " CASCADE").update();
      jdbc.sql("DROP TABLE IF EXISTS projection.participant_units_t_" + slug + " CASCADE").update();
      jdbc.sql("DELETE FROM projection.partitioned_tenant WHERE tenant_id = :t")
          .param("t", tenantId)
          .update();
    }
    tenantsCreated.clear();
  }

  // -------------------------------------------------------------------------------------------
  // "Paging 100,000 movements returns every row exactly once."
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("paging 100,000 movements returns every one exactly once")
  void pagingOneHundredThousandMovementsReturnsEveryRowOnce() {
    Fixture fixture = enrolled();
    LocalDate month = LocalDate.of(2026, 7, 1);
    int count = 100_000;
    insertAllotments(fixture, month, count, 0);

    List<String> seen = pageThrough(query(fixture.participantId, PAGE_SIZE, Optional.empty()));

    assertThat(seen).hasSize(count);
    assertThat(new HashSet<>(seen)).as("no movement is returned twice").hasSize(count);
  }

  @Test
  @DisplayName("pages descend, and every page but the last is full")
  void pagesDescendAndAreFull() {
    Fixture fixture = enrolled();
    insertAllotments(fixture, LocalDate.of(2026, 7, 1), 450, 0);

    MovementQuery query = query(fixture.participantId, PAGE_SIZE, Optional.empty());
    MovementPage first = movements.movements(query);
    assertThat(first.items()).hasSize(PAGE_SIZE);
    assertThat(first.hasMore()).isTrue();

    MovementPage second =
        movements.movements(query.continuingFrom(first.nextCursor().orElseThrow()));
    assertThat(second.items()).hasSize(PAGE_SIZE);

    MovementPage third =
        movements.movements(query.continuingFrom(second.nextCursor().orElseThrow()));
    assertThat(third.items()).hasSize(50);
    assertThat(third.hasMore()).isFalse();
    assertThat(third.nextCursor()).isEmpty();

    // Strictly descending by ledger timestamp, across the page boundary as well as within a page.
    List<String> ordered = new ArrayList<>();
    first.items().forEach(m -> ordered.add(m.ledgerTimestamp()));
    second.items().forEach(m -> ordered.add(m.ledgerTimestamp()));
    third.items().forEach(m -> ordered.add(m.ledgerTimestamp()));
    for (int i = 1; i < ordered.size(); i++) {
      assertThat(new java.math.BigInteger(ordered.get(i)))
          .isLessThan(new java.math.BigInteger(ordered.get(i - 1)));
    }
  }

  // -------------------------------------------------------------------------------------------
  // "Concurrent inserts mid-pagination cause no duplicates or skips."
  // -------------------------------------------------------------------------------------------

  /**
   * Keyset pagination descends from a position the first page fixed, so a movement committed while
   * a client is paging is newer than that position and is simply not in this pass. The failure this
   * rules out is the one {@code OFFSET} has: an insert above the reader shifts every later page and
   * pushes a row past it unseen, or repeats one it already had.
   */
  @Test
  @DisplayName("movements inserted mid-pagination cause neither a duplicate nor a skip")
  void concurrentInsertsCauseNoDuplicatesOrSkips() {
    Fixture fixture = enrolled();
    LocalDate month = LocalDate.of(2026, 7, 1);
    int original = 500;
    insertAllotments(fixture, month, original, 0);

    MovementQuery query = query(fixture.participantId, 50, Optional.empty());
    List<String> seen = new ArrayList<>();
    MovementPage page = movements.movements(query);
    page.items().forEach(movement -> seen.add(movement.transferId()));

    // Two more pages' worth of movements arrive, all newer than anything already there.
    insertAllotments(fixture, month, 300, original);

    Optional<String> cursor = page.nextCursor();
    while (cursor.isPresent()) {
      page = movements.movements(query.continuingFrom(cursor.get()));
      page.items().forEach(movement -> seen.add(movement.transferId()));
      cursor = page.nextCursor();
    }

    assertThat(seen).hasSize(original);
    assertThat(new HashSet<>(seen)).hasSize(original);

    // And a fresh listing does see them, so they were genuinely committed rather than absent.
    assertThat(pageThrough(query(fixture.participantId, PAGE_SIZE, Optional.empty())))
        .hasSize(original + 300);
  }

  // -------------------------------------------------------------------------------------------
  // "Tampered cursor → 400." (the HTTP status is MovementControllerTest's; this is the cause)
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("an edited cursor is refused rather than paged from")
  void anEditedCursorIsRefused() {
    Fixture fixture = enrolled();
    insertAllotments(fixture, LocalDate.of(2026, 7, 1), 120, 0);

    MovementQuery query = query(fixture.participantId, 50, Optional.empty());
    String issued = movements.movements(query).nextCursor().orElseThrow();
    String edited = issued.substring(0, issued.length() - 2) + (issued.endsWith("A") ? "BC" : "AA");

    assertThatThrownBy(() -> movements.movements(query.continuingFrom(edited)))
        .isInstanceOf(TamperedCursorException.class);
    assertThat(movements.movements(query.continuingFrom(issued)).items())
        .as("the unedited cursor still works, so the refusal is about the edit")
        .isNotEmpty();
  }

  // -------------------------------------------------------------------------------------------
  // "EXPLAIN shows partition pruning for a single-month query."
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a single-month query is pruned to that month's partition, and uses its index")
  void aSingleMonthQueryIsPrunedToOnePartition() {
    Fixture fixture = enrolled();
    insertAllotments(fixture, LocalDate.of(2026, 7, 1), 2_000, 0);
    insertAllotments(fixture, LocalDate.of(2026, 8, 1), 2_000, 2_000);
    jdbc.sql("ANALYZE projection.transfer").update();

    MovementQuery august =
        new MovementQuery(
            fixture.participantId,
            Optional.of(LocalDate.of(2026, 8, 1)),
            Optional.of(LocalDate.of(2026, 8, 31)),
            Optional.empty(),
            Optional.empty(),
            PAGE_SIZE,
            Optional.empty());

    String slug = slugOf(fixture.tenantId);
    MovementCursor midway =
        MovementCursor.of(movements.movements(august).items().get(PAGE_SIZE - 1));

    // Both the first page and a continuation page: the continuation carries the keyset row
    // comparison, which the first page does not, and it is the shape most pages actually have.
    for (Optional<MovementCursor> cursor :
        List.of(Optional.<MovementCursor>empty(), Optional.of(midway))) {
      String plan = explain(fixture, august, cursor);
      assertThat(plan)
          .as("the August leaf is read (cursor present: %s)", cursor.isPresent())
          .contains("transfer_t_" + slug + "_202608");
      assertThat(plan)
          .as("the July leaf is pruned away, not scanned and discarded")
          .doesNotContain("transfer_t_" + slug + "_202607");
      assertThat(plan)
          .as("V23's account indexes serve the query rather than a sequential scan")
          .contains("Index")
          .doesNotContain("Seq Scan");
    }

    // And the answer is the August rows only.
    assertThat(movements.movements(august).items()).hasSize(PAGE_SIZE);
    assertThat(pageThrough(august)).hasSize(2_000);
  }

  // -------------------------------------------------------------------------------------------
  // Filters, and both legs of a vesting transfer.
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a vesting transfer is two movements — a debit on unvested, a credit on vested")
  void aVestingTransferIsBothLegs() {
    Fixture fixture = enrolled();
    LocalDate valueDate = LocalDate.of(2026, 7, 15);
    insertVesting(fixture, valueDate);

    List<Movement> page =
        movements.movements(query(fixture.participantId, 50, Optional.empty())).items();

    assertThat(page).hasSize(2);
    assertThat(page)
        .extracting(Movement::holdingAccount, Movement::direction, Movement::transferCode)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                HoldingAccount.UNVESTED, MovementDirection.DEBIT, MovementTransferCode.VESTING),
            org.assertj.core.groups.Tuple.tuple(
                HoldingAccount.VESTED, MovementDirection.CREDIT, MovementTransferCode.VESTING));
    assertThat(page).extracting(Movement::transferId).hasSize(2);
    assertThat(page.get(0).transferId())
        .as("one transfer, two movements")
        .isEqualTo(page.get(1).transferId());
    assertThat(page).allSatisfy(movement -> assertThat(movement.navVersionId()).isEmpty());
  }

  @Test
  @DisplayName("the transfer-code filter narrows to one kind of movement")
  void theTransferCodeFilterNarrows() {
    Fixture fixture = enrolled();
    insertAllotments(fixture, LocalDate.of(2026, 7, 1), 40, 0);
    insertVesting(fixture, LocalDate.of(2026, 7, 15));

    MovementQuery vestingOnly =
        new MovementQuery(
            fixture.participantId,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(MovementTransferCode.VESTING),
            50,
            Optional.empty());

    assertThat(movements.movements(vestingOnly).items())
        .hasSize(2)
        .allSatisfy(
            movement ->
                assertThat(movement.transferCode()).isEqualTo(MovementTransferCode.VESTING));
    assertThat(movements.movements(query(fixture.participantId, 200, Optional.empty())).items())
        .hasSize(42);
  }

  @Test
  @DisplayName("one participant never sees another's movements, even in the same organization")
  void aHistoryIsScopedToOneParticipant() {
    Fixture fixture = enrolled();
    insertAllotments(fixture, LocalDate.of(2026, 7, 1), 30, 0);

    String otherRef = "EMP-" + UUID.randomUUID();
    UUID otherId = enrolParticipant(fixture.organizationId, otherRef);

    assertThat(movements.movements(query(ParticipantId.of(otherId), 50, Optional.empty())).items())
        .isEmpty();
    assertThat(movements.movements(query(fixture.participantId, 50, Optional.empty())).items())
        .hasSize(30);
  }

  /**
   * <b>The NAV version is seeded by this test, because nothing else can produce it yet.</b> The CDC
   * change event carries no NAV version, so {@code JdbcProjectionSink} leaves {@code
   * projection.transfer.nav_version_id} null on every row the real pipeline writes (Task 5.4) — a
   * movement served in production reports none, and the contract now says so. What this asserts is
   * the only thing that is this task's to assert: the column is read and carried through faithfully
   * when a row does have one.
   */
  @Test
  @DisplayName("a NAV version is carried through when the projected row has one")
  void aNavVersionIsCarriedThroughWhenPresent() {
    Fixture fixture = enrolled();
    insertAllotments(fixture, LocalDate.of(2026, 7, 1), 1, 0);

    assertThat(movements.movements(query(fixture.participantId, 50, Optional.empty())).items())
        .singleElement()
        .satisfies(
            movement -> {
              assertThat(movement.navVersionId()).contains(4242L);
              assertThat(movement.fundId()).isEqualTo(fixture.fundId);
              assertThat(movement.units().scaled()).isEqualTo(UNITS_PER_MOVEMENT);
            });
  }

  // -------------------------------------------------------------------------------------------
  // Fixture helpers. Everything below creates ids this test alone asserts over.
  // -------------------------------------------------------------------------------------------

  private record Fixture(
      OrganizationId organizationId,
      String internalRef,
      ParticipantId participantId,
      FundId fundId,
      long unitLedgerId,
      String tenantId) {}

  private Fixture enrolled() {
    OrganizationId organization = new OrganizationId(UUID.randomUUID());
    registerOrganization(organization);
    String internalRef = "EMP-" + UUID.randomUUID();
    UUID participantId = enrolParticipant(organization, internalRef);
    long unitLedgerId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000) + 1001;
    FundId fundId = registerFund(unitLedgerId);
    String tenantId = TenantRef.derive(LEDGER_NAMESPACE, organization).asUnsignedDecimal();

    jdbc.sql("SELECT projection.ensure_tenant_partitions(:t)")
        .param("t", tenantId)
        .query()
        .listOfRows();
    tenantsCreated.add(tenantId);
    for (LocalDate month : List.of(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1))) {
      jdbc.sql("SELECT projection.ensure_transfer_month(:t, :m)")
          .param("t", tenantId)
          .param("m", month)
          .query()
          .listOfRows();
    }
    return new Fixture(
        organization, internalRef, ParticipantId.of(participantId), fundId, unitLedgerId, tenantId);
  }

  /**
   * {@code count} contribution allotments crediting the participant's vested holding, spread over
   * the twenty business days of {@code month}, one ledger timestamp and one transfer id apart.
   *
   * @param ordinalOffset shifts the ids and timestamps so a second call adds strictly newer
   *     movements rather than colliding with the first
   */
  private void insertAllotments(Fixture fixture, LocalDate month, int count, int ordinalOffset) {
    jdbc.sql(
            """
            INSERT INTO projection.transfer
              (transfer_id, tenant_id, ledger_timestamp, ledger_id, debit_account_id,
               credit_account_id, amount_scaled, code, booking_date, value_date, nav_version_id)
            SELECT CAST(:idBase AS numeric) + g, :tenantId, CAST(:idBase AS numeric) + g,
                   :ledgerId, CAST(:debit AS numeric), CAST(:credit AS numeric),
                   :units, :code, :month + ((g - 1) % 20), :month + ((g - 1) % 20), 4242
              FROM generate_series(:from, :to) AS g
            """)
        .param("idBase", idBase(month))
        .param("tenantId", fixture.tenantId)
        .param("ledgerId", fixture.unitLedgerId)
        .param("debit", unitsInIssue(fixture))
        .param("credit", holding(fixture, HoldingAccount.VESTED))
        .param("units", UNITS_PER_MOVEMENT)
        .param("code", LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT.code())
        .param("month", month)
        .param("from", ordinalOffset + 1)
        .param("to", ordinalOffset + count)
        .update();
  }

  /**
   * One vesting transfer: the participant's unvested account debited, their vested one credited.
   */
  private void insertVesting(Fixture fixture, LocalDate valueDate) {
    jdbc.sql(
            """
            INSERT INTO projection.transfer
              (transfer_id, tenant_id, ledger_timestamp, ledger_id, debit_account_id,
               credit_account_id, amount_scaled, code, booking_date, value_date, nav_version_id)
            VALUES
              (CAST(:id AS numeric), :tenantId, CAST(:id AS numeric), :ledgerId,
               CAST(:debit AS numeric), CAST(:credit AS numeric), :units, :code,
               :valueDate, :valueDate, NULL)
            """)
        .param("id", idBase(valueDate) + 900_000L)
        .param("tenantId", fixture.tenantId)
        .param("ledgerId", fixture.unitLedgerId)
        .param("debit", holding(fixture, HoldingAccount.UNVESTED))
        .param("credit", holding(fixture, HoldingAccount.VESTED))
        .param("units", UNITS_PER_MOVEMENT)
        .param("code", LedgerCodes.TransferCode.VESTING.code())
        .param("valueDate", valueDate)
        .update();
  }

  /**
   * The base for a month's ids and timestamps. One number serves as both: {@code ledger_timestamp}
   * is {@code numeric(20,0)} (TigerBeetle's u64 nanosecond clock), so it must stay inside 20
   * digits, and a plausible nanosecond epoch value plus a per-month stride keeps every month's
   * movements strictly newer than the month before. Uniqueness only has to hold within a fixture:
   * every fixture owns its own tenant partition, and the primary key is scoped by tenant.
   */
  private static long idBase(LocalDate month) {
    long monthOrdinal = month.getYear() * 12L + month.getMonthValue();
    return 1_800_000_000_000_000_000L + monthOrdinal * 10_000_000L;
  }

  private String holding(Fixture fixture, HoldingAccount account) {
    return accountIds
        .derive(
            account.accountCode(),
            new ParticipantScope(fixture.organizationId, fixture.internalRef)
                .forFund(fixture.fundId))
        .asUnsignedDecimal();
  }

  private String unitsInIssue(Fixture fixture) {
    return accountIds
        .derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fixture.fundId.toString())
        .asUnsignedDecimal();
  }

  private String slugOf(String tenantId) {
    return jdbc.sql("SELECT left(md5(:t), 16)").param("t", tenantId).query(String.class).single();
  }

  /**
   * {@code EXPLAIN} of the statement the service really builds — the account list comes from the
   * injected {@link FundDirectory} and the same derivation {@code MovementService} uses, so the
   * branch count and the bind parameters are the production ones rather than a two-account
   * approximation that would prune more easily than the real query.
   */
  private String explain(Fixture fixture, MovementQuery query, Optional<MovementCursor> cursor) {
    List<String> accounts = new ArrayList<>();
    ParticipantScope scope = new ParticipantScope(fixture.organizationId, fixture.internalRef);
    for (FundId fundId : query.fundId().map(List::of).orElseGet(funds::listFundIds)) {
      for (HoldingAccount account : HoldingAccount.values()) {
        accounts.add(
            accountIds.derive(account.accountCode(), scope.forFund(fundId)).asUnsignedDecimal());
      }
    }
    MovementPageStatement statement =
        MovementPageStatement.build(fixture.tenantId, accounts, query, cursor, query.limit() + 3);
    var spec = jdbc.sql("EXPLAIN " + statement.sql());
    for (var parameter : statement.parameters().entrySet()) {
      spec = spec.param(parameter.getKey(), parameter.getValue());
    }
    return String.join("\n", spec.query(String.class).list());
  }

  private List<String> pageThrough(MovementQuery query) {
    List<String> seen = new ArrayList<>();
    MovementQuery current = query;
    while (true) {
      MovementPage page = movements.movements(current);
      page.items().forEach(movement -> seen.add(movement.transferId()));
      if (!page.hasMore()) {
        return seen;
      }
      current = query.continuingFrom(page.nextCursor().orElseThrow());
    }
  }

  private static MovementQuery query(
      ParticipantId participantId, int limit, Optional<String> cursor) {
    return new MovementQuery(
        participantId,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        limit,
        cursor);
  }

  private void registerOrganization(OrganizationId organizationId) {
    Timestamp now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS));
    jdbc.sql(
            """
            INSERT INTO master.organization (
                organization_id, tax_id_scheme, tax_id, legal_name, status, created_at, updated_at)
            VALUES (:organizationId, 'RFC', :taxId, 'Acme S.A. de C.V.', 'ACTIVE', :now, :now)
            """)
        .param("organizationId", organizationId.value())
        .param("taxId", "RFC-" + UUID.randomUUID())
        .param("now", now)
        .update();
  }

  private UUID enrolParticipant(OrganizationId organizationId, String internalRef) {
    Timestamp now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS));
    jdbc.sql(
            """
            INSERT INTO master.participant (
                organization_id, participant_internal_ref, plan_id, participant_payroll_ref,
                participant_status, employment_status, employment_start_date, enrolment_date,
                created_at, updated_at)
            VALUES (:organizationId, :internalRef, 'PLAN-A', :payrollRef,
                    'ACTIVE', 'ACTIVE', :employmentStart, :enrolment, :now, :now)
            """)
        .param("organizationId", organizationId.value())
        .param("internalRef", internalRef)
        .param("payrollRef", "PR-" + UUID.randomUUID())
        .param("employmentStart", LocalDate.of(2024, 1, 15))
        .param("enrolment", LocalDate.of(2024, 2, 1))
        .param("now", now)
        .update();
    return jdbc.sql(
            """
            SELECT participant_id FROM master.participant
             WHERE organization_id = :organizationId AND participant_internal_ref = :internalRef
            """)
        .param("organizationId", organizationId.value())
        .param("internalRef", internalRef)
        .query(UUID.class)
        .single();
  }

  private FundId registerFund(long unitLedgerId) {
    FundId fundId = FundId.of(UUID.randomUUID());
    Timestamp now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS));
    jdbc.sql(
            """
            INSERT INTO reference.fund_identity (
                fund_id, unit_ledger_id, base_currency, unit_scale, inception_date,
                opening_nav_scaled, created_at)
            VALUES (:fundId, :unitLedgerId, 'MXN', 100000000, :inceptionDate, 1000000000, :now)
            """)
        .param("fundId", fundId.value())
        .param("unitLedgerId", unitLedgerId)
        .param("inceptionDate", LocalDate.of(2025, 1, 1))
        .param("now", now)
        .update();
    jdbc.sql(
            """
            INSERT INTO reference.fund (
                fund_id, effective_from, version, name, vehicle_type, status, published_at)
            VALUES (:fundId, :effectiveFrom, 1, :name, 'CAJA_DE_AHORRO', 'ACTIVE', :now)
            """)
        .param("fundId", fundId.value())
        .param("effectiveFrom", LocalDate.of(2025, 1, 1))
        .param("name", "Movement test fund " + fundId.value())
        .param("now", now)
        .update();
    fundsRegistered.add(fundId);
    return fundId;
  }
}

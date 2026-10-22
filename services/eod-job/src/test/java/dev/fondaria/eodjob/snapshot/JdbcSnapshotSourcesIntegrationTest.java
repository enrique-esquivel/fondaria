package dev.fondaria.eodjob.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import dev.fondaria.eodjob.reconciliation.HoldingAccountSource;
import dev.fondaria.eodjob.reconciliation.HoldingKey;
import dev.fondaria.eodjob.support.ServiceIntegrationTest;
import dev.fondaria.ledger.TenantRefDeriver;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The three reads a snapshot position is assembled from, against real PostgreSQL: units as-of from
 * {@code projection.participant_units}, lots from {@code workflow.allotment} joined to {@code
 * reference.fund_nav}, and the live price from {@code reference.fund_nav}.
 *
 * <p>Each is exercised against the SQL the service actually runs, because each depends on a
 * property no in-memory double can prove: {@code DISTINCT ON} over a sparse table returning the
 * latest row per account, a row-wise range matching exactly a keyset page, and a correction being
 * the row nothing points at.
 *
 * <p>Every id is fresh and every assertion is over rows this test wrote (AGENTS.md §4).
 */
@SpringBootTest
class JdbcSnapshotSourcesIntegrationTest extends ServiceIntegrationTest {

  private static final ZoneId ZONE = ZoneId.of("America/Mexico_City");
  private static final LocalDate MONDAY = LocalDate.of(2026, 3, 2);
  private static final LocalDate TUESDAY = LocalDate.of(2026, 3, 3);
  private static final LocalDate WEDNESDAY = LocalDate.of(2026, 3, 4);

  @Autowired private PositionUnitsSource positionUnits;
  @Autowired private CostBasisSource costBasis;
  @Autowired private SnapshotNavSource navs;
  @Autowired private HoldingAccountSource holdings;
  @Autowired private JdbcClient jdbc;

  private OrganizationId organizationId;
  private FundId fundId;
  private long unitLedgerId;
  private UUID batchId;
  private String tenantId;

  @BeforeEach
  void freshFixture() {
    organizationId = new OrganizationId(UUID.randomUUID());
    fundId = new FundId(UUID.randomUUID());
    unitLedgerId = ThreadLocalRandom.current().nextLong(1_001L, 2_000_000_000L);
    batchId = UUID.randomUUID();
    tenantId = new TenantRefDeriver(LEDGER_NAMESPACE).derive(organizationId).asUnsignedDecimal();
    seedPlatformRows();
  }

  // ---- units as-of ---------------------------------------------------------------------------

  @Test
  @DisplayName("reads the latest holding at or before the date, from a sparse table")
  void readsTheLatestHoldingAtOrBeforeTheDate() {
    String account = freshAccountId();
    projectedUnits(account, MONDAY, 100_00000000L);
    projectedUnits(account, WEDNESDAY, 175_00000000L);

    // Monday: the Monday row. Tuesday: still the Monday row — the participant was dormant, and
    // this is exactly what makes the snapshot dense without the projection storing a row per day.
    assertThat(positionUnits.unitsAsOf(tenantId, List.of(account), MONDAY))
        .containsEntry(account, 100_00000000L);
    assertThat(positionUnits.unitsAsOf(tenantId, List.of(account), TUESDAY))
        .containsEntry(account, 100_00000000L);
    assertThat(positionUnits.unitsAsOf(tenantId, List.of(account), WEDNESDAY))
        .containsEntry(account, 175_00000000L);
  }

  @Test
  @DisplayName("an account with no row at or before the date is absent, not zero-by-accident")
  void anAccountWithNoRowIsAbsent() {
    String account = freshAccountId();
    projectedUnits(account, WEDNESDAY, 5L);

    assertThat(positionUnits.unitsAsOf(tenantId, List.of(account), MONDAY)).isEmpty();
  }

  @Test
  @DisplayName("reads a whole page of accounts in one statement")
  void readsAPageOfAccountsAtOnce() {
    List<String> accounts =
        List.of(freshAccountId(), freshAccountId(), freshAccountId(), freshAccountId());
    for (int i = 0; i < accounts.size(); i++) {
      projectedUnits(accounts.get(i), MONDAY, (i + 1) * 10L);
    }

    Map<String, Long> units = positionUnits.unitsAsOf(tenantId, accounts, MONDAY);

    assertThat(units).hasSize(4);
    assertThat(units.get(accounts.get(3))).isEqualTo(40L);
  }

  @Test
  @DisplayName("never reads another tenant's partition")
  void isScopedToOneTenant() {
    String account = freshAccountId();
    projectedUnits(account, MONDAY, 42L);

    String otherTenant =
        new TenantRefDeriver(LEDGER_NAMESPACE)
            .derive(new OrganizationId(UUID.randomUUID()))
            .asUnsignedDecimal();

    assertThat(positionUnits.unitsAsOf(otherTenant, List.of(account), MONDAY)).isEmpty();
  }

  // ---- cost basis ----------------------------------------------------------------------------

  @Test
  @DisplayName("returns every lot in the page's range, at the price each was issued at")
  void returnsLotsInRange() {
    long cheapNav = strikeNav(MONDAY, 50000000L);
    long dearNav = strikeNav(TUESDAY, 1_50000000L);
    allot("P-001", "EMPLOYEE", cheapNav, 100_00000000L, MONDAY);
    allot("P-001", "EMPLOYER", dearNav, 40_00000000L, TUESDAY);
    allot("P-002", "EMPLOYEE", dearNav, 10_00000000L, TUESDAY);

    List<AllotmentLot> lots =
        costBasis.lotsFor(holding("P-001"), holding("P-002"), endOf(WEDNESDAY));

    assertThat(lots).hasSize(3);
    assertThat(lots)
        .extracting(AllotmentLot::navScaled)
        .as("the price each lot was issued at, not today's")
        .containsExactlyInAnyOrder(50000000L, 1_50000000L, 1_50000000L);
    assertThat(lots.stream().filter(lot -> lot.holding().equals(holding("P-001"))).toList())
        .hasSize(2);
  }

  @Test
  @DisplayName("the range is inclusive at both ends — a page's first and last holdings are in it")
  void theRangeIsInclusive() {
    long navVersionId = strikeNav(MONDAY, 1_00000000L);
    allot("P-001", "EMPLOYEE", navVersionId, 1L, MONDAY);
    allot("P-002", "EMPLOYEE", navVersionId, 2L, MONDAY);
    allot("P-003", "EMPLOYEE", navVersionId, 3L, MONDAY);

    assertThat(costBasis.lotsFor(holding("P-001"), holding("P-003"), endOf(MONDAY))).hasSize(3);
    assertThat(costBasis.lotsFor(holding("P-002"), holding("P-002"), endOf(MONDAY)))
        .extracting(AllotmentLot::units)
        .containsExactly(Units.of(2L));
  }

  @Test
  @DisplayName("a lot dealt at an earlier NAV but posted later is bounded by when it was posted")
  void isBoundedByWhenTheLotWasPosted() {
    // The whole point of bounding on allotted_at rather than on the NAV's valuation date: a batch
    // is priced at the NAV struck for its DEALING date, and allotment-job may post it days later.
    // projection.participant_units files the units under the posting date, so the cost has to be
    // filed there too or the snapshot reports a cost with no units behind it.
    long mondayNav = strikeNav(MONDAY, 1_00000000L);
    allot("P-001", "EMPLOYEE", mondayNav, 100L, MONDAY);
    allot("P-001", "EMPLOYER", mondayNav, 50L, WEDNESDAY);

    assertThat(costBasis.lotsFor(holding("P-001"), holding("P-001"), endOf(MONDAY)))
        .as("both lots carry Monday's price; only the one posted by Monday belongs to Monday")
        .hasSize(1);
    assertThat(costBasis.lotsFor(holding("P-001"), holding("P-001"), endOf(WEDNESDAY))).hasSize(2);
  }

  @Test
  @DisplayName("a participant reference that reorders under a locale still bounds its own page")
  void theRangeIsCollationStable() {
    // "EMP.001" and "EMP-001" sort differently under en_US.UTF-8 and under C. The keyset scan and
    // this range must agree, or a page's boundaries select somebody else's lots — or nobody's.
    long navVersionId = strikeNav(MONDAY, 1_00000000L);
    allot("EMP-001", "EMPLOYEE", navVersionId, 1L, MONDAY);
    allot("EMP.001", "EMPLOYEE", navVersionId, 2L, MONDAY);

    List<HoldingKey> page = holdings.nextPage(Optional.empty(), Integer.MAX_VALUE);
    List<HoldingKey> mine =
        page.stream().filter(key -> key.organizationId().equals(organizationId)).toList();

    assertThat(mine).hasSize(2);
    assertThat(costBasis.lotsFor(mine.get(0), mine.get(mine.size() - 1), endOf(MONDAY)))
        .as("the page's own first and last key must enclose every lot the page covers")
        .hasSize(2);
  }

  // ---- the price -----------------------------------------------------------------------------

  @Test
  @DisplayName("carries the latest price at or before the date forward")
  void carriesTheLatestPriceForward() {
    strikeNav(MONDAY, 1_07000000L);

    assertThat(navs.liveNavsAsOf(TUESDAY).get(fundId).nav().scaled()).isEqualTo(1_07000000L);
    assertThat(navs.liveNavsAsOf(TUESDAY).get(fundId).valuationDate()).isEqualTo(MONDAY);
    assertThat(navs.liveNavsAsOf(MONDAY.minusDays(1)))
        .as("a fund with no price on or before the date is absent, never zero")
        .doesNotContainKey(fundId);
  }

  @Test
  @DisplayName("a corrected price supersedes the original, and the correction is what is read")
  void readsTheLivePriceNotTheOriginal() {
    long original = strikeNav(MONDAY, 1_00000000L);
    long correction =
        jdbc.sql(
                """
                INSERT INTO reference.fund_nav
                  (fund_id, valuation_date, nav_scaled, units_in_issue, source, supersedes_id,
                   reason, published_at)
                VALUES (:fundId, :date, 90000000, 0, 'TEST', :supersedes, 'a bad price', now())
                RETURNING nav_version_id
                """)
            .param("fundId", fundId.value())
            .param("date", MONDAY)
            .param("supersedes", original)
            .query(Long.class)
            .single();

    SnapshotNav live = navs.liveNavsAsOf(MONDAY).get(fundId);

    assertThat(live.navVersionId())
        .as("live is the row nothing points at — reading supersedes_id IS NULL gives the opposite")
        .isEqualTo(correction);
    assertThat(live.nav().scaled()).isEqualTo(90000000L);
  }

  // ---- fixture -------------------------------------------------------------------------------

  private HoldingKey holding(String participantInternalRef) {
    return new HoldingKey(organizationId, participantInternalRef, fundId);
  }

  private void seedPlatformRows() {
    jdbc.sql(
            """
            INSERT INTO master.organization (organization_id, tax_id_scheme, tax_id, legal_name,
                                             status, created_at, updated_at)
            VALUES (:organizationId, 'RFC', :taxId, 'Snapshot Sources SA de CV', 'ACTIVE',
                    now(), now())
            """)
        .param("organizationId", organizationId.value())
        .param("taxId", freshRfc())
        .update();
    jdbc.sql(
            """
            INSERT INTO reference.fund_identity
              (fund_id, unit_ledger_id, base_currency, unit_scale, inception_date,
               opening_nav_scaled, created_at)
            VALUES (:fundId, :unitLedgerId, 'MXN', :unitScale, DATE '2020-01-01', 100000000, now())
            """)
        .param("fundId", fundId.value())
        .param("unitLedgerId", unitLedgerId)
        .param("unitScale", Units.SCALE)
        .update();
    jdbc.sql(
            """
            INSERT INTO workflow.contribution_batch
              (batch_id, organization_id, object_key, checksum_sha256, status, submitted_at,
               status_updated_at)
            VALUES (:batchId, :organizationId, :objectKey, :checksum, 'ALLOTTED', now(), now())
            """)
        .param("batchId", batchId)
        .param("organizationId", organizationId.value())
        .param("objectKey", "sources-fixture/" + batchId)
        .param("checksum", "0".repeat(64))
        .update();
    jdbc.sql("SELECT projection.ensure_tenant_partitions(:tenantId)")
        .param("tenantId", tenantId)
        .query((rs, rowNum) -> 1)
        .optional();
  }

  private long strikeNav(LocalDate valuationDate, long navScaled) {
    return jdbc.sql(
            """
            INSERT INTO reference.fund_nav
              (fund_id, valuation_date, nav_scaled, units_in_issue, source, published_at)
            VALUES (:fundId, :date, :navScaled, 0, 'TEST', now())
            RETURNING nav_version_id
            """)
        .param("fundId", fundId.value())
        .param("date", valuationDate)
        .param("navScaled", navScaled)
        .query(Long.class)
        .single();
  }

  private void allot(
      String participantRef,
      String source,
      long navVersionId,
      long unitsScaled,
      LocalDate postedOn) {
    jdbc.sql(
            """
            INSERT INTO workflow.allotment
              (organization_id, batch_id, participant_internal_ref, source, fund_id,
               nav_version_id, units_scaled, vested, transfer_id, allotted_at)
            VALUES (:organizationId, :batchId, :participantRef, :source, :fundId, :navVersionId,
                    :unitsScaled, true, gen_random_uuid(), :allottedAt)
            """)
        .param("organizationId", organizationId.value())
        .param("batchId", batchId)
        .param("participantRef", participantRef)
        .param("source", source)
        .param("fundId", fundId.value())
        .param("navVersionId", navVersionId)
        .param("unitsScaled", unitsScaled)
        .param("allottedAt", Timestamp.from(middayOf(postedOn)))
        .update();
  }

  /** Midday local on a date — comfortably inside its business date, either side of the cut-off. */
  private static Instant middayOf(LocalDate date) {
    return date.atTime(LocalTime.NOON).atZone(ZONE).toInstant();
  }

  /** An instant certainly after the end of a business date, as the builder computes it. */
  private static Instant endOf(LocalDate businessDate) {
    return businessDate.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  private void projectedUnits(String accountId, LocalDate effectiveDate, long unitsScaled) {
    jdbc.sql(
            """
            INSERT INTO projection.participant_units
              (tenant_id, participant_id, fund_id, effective_date, units_scaled,
               last_transfer_id, last_ledger_timestamp, prev_row_hash, row_hash)
            VALUES (:tenantId, CAST(:accountId AS numeric), :fundId, :effectiveDate, :unitsScaled,
                    CAST(:accountId AS numeric), 1800000000000000000, NULL, '\\x00'::bytea)
            """)
        .param("tenantId", tenantId)
        .param("accountId", accountId)
        .param("fundId", unitLedgerId)
        .param("effectiveDate", effectiveDate)
        .param("unitsScaled", unitsScaled)
        .update();
  }

  /** A u128-shaped id no other run derived: the high bits come from a fresh random UUID. */
  private static String freshAccountId() {
    UUID id = UUID.randomUUID();
    return new java.math.BigInteger(1, toBytes(id)).toString();
  }

  private static byte[] toBytes(UUID id) {
    byte[] bytes = new byte[16];
    long msb = id.getMostSignificantBits();
    long lsb = id.getLeastSignificantBits();
    for (int i = 0; i < 8; i++) {
      bytes[i] = (byte) (msb >>> (56 - 8 * i));
      bytes[8 + i] = (byte) (lsb >>> (56 - 8 * i));
    }
    return bytes;
  }

  /** A well-formed persona-moral RFC nobody has used. */
  private static String freshRfc() {
    String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    StringBuilder rfc = new StringBuilder(12);
    for (int i = 0; i < 3; i++) {
      rfc.append(letters.charAt(ThreadLocalRandom.current().nextInt(letters.length())));
    }
    rfc.append("010101");
    for (int i = 0; i < 3; i++) {
      rfc.append(letters.charAt(ThreadLocalRandom.current().nextInt(letters.length())));
    }
    return rfc.toString();
  }
}

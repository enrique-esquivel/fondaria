package dev.fondaria.valuationapi.correction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import dev.fondaria.rules.FundNav;
import dev.fondaria.rules.FundNavRepository;
import dev.fondaria.valuation.NavCache;
import dev.fondaria.valuationapi.support.ValuationIntegrationTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * End to end against a real PostgreSQL: Task 20.5's four acceptance criteria, wired through {@link
 * NavCorrectionService} and {@link NavCache} exactly as production does, not through the repository
 * directly (that split is {@code JdbcFundNavRepositoryIntegrationTest}'s).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NavCorrectionIntegrationTest extends ValuationIntegrationTest {

  @Autowired private NavCorrectionService corrections;
  @Autowired private FundNavRepository fundNavRepository;
  @Autowired private NavCache navCache;
  @Autowired private AffectedAllotmentRepository affectedAllotments;
  @Autowired private JdbcClient jdbc;
  @Autowired private Clock clock;

  @Test
  @DisplayName("a correction inserts a row and leaves the original readable and intact")
  void aCorrectionInsertsARowAndLeavesTheOriginalReadableAndIntact() {
    FundId fundId = registerFund();
    LocalDate date = LocalDate.of(2026, 3, 2);
    FundNav original =
        fundNavRepository.strike(
            strikeCandidate(fundId, date, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN)));

    FundNav corrected =
        corrections.correct(fundId, date, Nav.of(9 * Nav.SCALE, CurrencyCode.MXN), "restated");

    assertThat(corrected.supersedesId()).isEqualTo(original.navVersionId());
    assertThat(fundNavRepository.findOriginal(fundId, date)).contains(original);
  }

  @Test
  @DisplayName("a correction with no reason is refused")
  void aCorrectionWithNoReasonIsRefused() {
    FundId fundId = registerFund();
    LocalDate date = LocalDate.of(2026, 3, 2);
    fundNavRepository.strike(
        strikeCandidate(fundId, date, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN)));

    assertThatThrownBy(
            () -> corrections.correct(fundId, date, Nav.of(9 * Nav.SCALE, CurrencyCode.MXN), ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("publishing a correction invalidates the cache immediately")
  void publishingACorrectionInvalidatesTheCacheImmediately() {
    FundId fundId = registerFund();
    LocalDate date = LocalDate.of(2026, 3, 2);
    fundNavRepository.strike(
        strikeCandidate(fundId, date, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN)));
    navCache.refresh(fundId);
    assertThat(navCache.current(fundId).orElseThrow().nav())
        .isEqualTo(Nav.of(10 * Nav.SCALE, CurrencyCode.MXN));

    corrections.correct(fundId, date, Nav.of(6 * Nav.SCALE, CurrencyCode.MXN), "provider error");

    // No refreshAll(), no interval elapsed -- correct() itself must have invalidated it.
    assertThat(navCache.current(fundId).orElseThrow().nav())
        .isEqualTo(Nav.of(6 * Nav.SCALE, CurrencyCode.MXN));
  }

  @Test
  @DisplayName("allotments made under the superseded NAV still name it, unchanged")
  void allotmentsMadeUnderTheSupersededNavStillNameItUnchanged() {
    FundId fundId = registerFund();
    LocalDate date = LocalDate.of(2026, 3, 2);
    FundNav original =
        fundNavRepository.strike(
            strikeCandidate(fundId, date, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN)));
    OrganizationId organizationId = registerOrganization();
    UUID batchId = registerAllottedBatch(organizationId);
    recordAllotment(organizationId, batchId, fundId, original.navVersionId());

    corrections.correct(fundId, date, Nav.of(9 * Nav.SCALE, CurrencyCode.MXN), "restated");

    Long navVersionOnAllotment =
        jdbc.sql("SELECT nav_version_id FROM workflow.allotment WHERE batch_id = :batchId")
            .param("batchId", batchId)
            .query(Long.class)
            .single();
    assertThat(navVersionOnAllotment).isEqualTo(original.navVersionId());
  }

  @Test
  @DisplayName("the list of allotments affected by a correction is queryable")
  void theListOfAllotmentsAffectedByACorrectionIsQueryable() {
    FundId fundId = registerFund();
    LocalDate date = LocalDate.of(2026, 3, 2);
    FundNav original =
        fundNavRepository.strike(
            strikeCandidate(fundId, date, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN)));
    OrganizationId organizationId = registerOrganization();
    UUID batchId = registerAllottedBatch(organizationId);
    recordAllotment(organizationId, batchId, fundId, original.navVersionId());
    // A second fund's allotment must never show up in the first fund's report.
    FundId otherFundId = registerFund();
    FundNav otherOriginal =
        fundNavRepository.strike(
            strikeCandidate(otherFundId, date, Nav.of(20 * Nav.SCALE, CurrencyCode.MXN)));
    UUID otherBatchId = registerAllottedBatch(organizationId);
    recordAllotment(organizationId, otherBatchId, otherFundId, otherOriginal.navVersionId());

    corrections.correct(fundId, date, Nav.of(9 * Nav.SCALE, CurrencyCode.MXN), "restated");

    List<AffectedAllotment> affected =
        affectedAllotments.findByNavVersionId(original.navVersionId());

    assertThat(affected).hasSize(1);
    assertThat(affected.get(0).batchId()).isEqualTo(batchId);
    // The other fund's own price was never corrected, but its allotment must still resolve
    // under its own nav_version_id and nowhere near the first fund's report.
    List<AffectedAllotment> unaffected =
        affectedAllotments.findByNavVersionId(otherOriginal.navVersionId());
    assertThat(unaffected).hasSize(1);
    assertThat(unaffected.get(0).batchId()).isEqualTo(otherBatchId);
  }

  private FundNav strikeCandidate(FundId fundId, LocalDate date, Nav nav) {
    return new FundNav(
        0L,
        fundId,
        date,
        nav,
        Units.of(1_000 * Units.SCALE),
        "MODEL_B",
        null,
        null,
        clock.instant());
  }

  private FundId registerFund() {
    FundId fundId = FundId.of(UUID.randomUUID());
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    jdbc.sql(
            """
            INSERT INTO reference.fund_identity (
                fund_id, unit_ledger_id, base_currency, unit_scale, inception_date,
                opening_nav_scaled, created_at)
            VALUES (:fundId, :unitLedgerId, 'MXN', 100000000, :inceptionDate, 1000000000, :now)
            """)
        .param("fundId", fundId.value())
        .param("unitLedgerId", Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000))
        .param("inceptionDate", LocalDate.of(2025, 1, 1))
        .param("now", Timestamp.from(now))
        .update();
    jdbc.sql(
            """
            INSERT INTO reference.fund (
                fund_id, effective_from, version, name, vehicle_type, status, published_at)
            VALUES (:fundId, :effectiveFrom, 1, :name, 'CAJA_DE_AHORRO', 'ACTIVE', :now)
            """)
        .param("fundId", fundId.value())
        .param("effectiveFrom", LocalDate.of(2025, 1, 1))
        .param("name", "Test fund " + fundId.value())
        .param("now", Timestamp.from(now))
        .update();
    return fundId;
  }

  private OrganizationId registerOrganization() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    jdbc.sql(
            """
            INSERT INTO master.organization (
                organization_id, tax_id_scheme, tax_id, legal_name, status, created_at, updated_at)
            VALUES (:organizationId, 'RFC', :taxId, 'Acme S.A. de C.V.', 'ACTIVE', :now, :now)
            """)
        .param("organizationId", organizationId.value())
        .param("taxId", "RFC-" + UUID.randomUUID())
        .param("now", Timestamp.from(now))
        .update();
    return organizationId;
  }

  private UUID registerAllottedBatch(OrganizationId organizationId) {
    UUID batchId = UUID.randomUUID();
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    jdbc.sql(
            """
            INSERT INTO workflow.contribution_batch (
                batch_id, organization_id, object_key, checksum_sha256, status,
                submitted_at, status_updated_at)
            VALUES (:batchId, :organizationId, :objectKey, :checksum, 'ALLOTTED', :now, :now)
            """)
        .param("batchId", batchId)
        .param("organizationId", organizationId.value())
        .param("objectKey", "contribution-batches/" + batchId + ".csv")
        .param("checksum", "a".repeat(64))
        .param("now", Timestamp.from(now))
        .update();
    return batchId;
  }

  private void recordAllotment(
      OrganizationId organizationId, UUID batchId, FundId fundId, long navVersionId) {
    jdbc.sql(
            """
            INSERT INTO workflow.allotment (
                organization_id, batch_id, participant_internal_ref, source, fund_id,
                nav_version_id, units_scaled, vested, transfer_id, allotted_at)
            VALUES (:organizationId, :batchId, :participantRef, 'EMPLOYEE', :fundId,
                    :navVersionId, :unitsScaled, true, :transferId, :allottedAt)
            """)
        .param("organizationId", organizationId.value())
        .param("batchId", batchId)
        .param("participantRef", "EMP-" + UUID.randomUUID())
        .param("fundId", fundId.value())
        .param("navVersionId", navVersionId)
        .param("unitsScaled", 100L * Units.SCALE)
        .param("transferId", UUID.randomUUID())
        .param("allottedAt", Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS)))
        .update();
  }
}

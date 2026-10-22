package dev.fondaria.eodjob.custodian;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.eodjob.support.ServiceIntegrationTest;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code reference.provider_account} against a real PostgreSQL ({@code V26}): resolution is always
 * as-of a date, exactly as {@code reference.plan_version} resolves.
 *
 * <p>Every assertion is over ids this test created (docs/ENGINEERING-RULES.md §4).
 */
@SpringBootTest
class JdbcProviderAccountDirectoryIntegrationTest extends ServiceIntegrationTest {

  @Autowired private ProviderAccountDirectory directory;
  @Autowired private JdbcClient jdbc;

  private FundId fund;

  @BeforeEach
  void freshFixture() {
    fund = registerFund();
  }

  @Test
  @DisplayName("resolves the highest (effective_from, version) not after the date asked for")
  void resolvesTheLatestVersionAsOfDate() {
    UUID v1 = insertAllParticipants(fund, LocalDate.of(2024, 1, 1), 1, "PROV-V1");
    UUID v2 = insertAllParticipants(fund, LocalDate.of(2024, 6, 1), 1, "PROV-V2");

    List<ProviderAccount> beforeAnyVersion = directory.activeAsOf(LocalDate.of(2023, 12, 31));
    assertThat(idsOf(beforeAnyVersion, fund)).doesNotContain(v1, v2);

    List<ProviderAccount> afterV1Only = directory.activeAsOf(LocalDate.of(2024, 3, 1));
    ProviderAccount resolved = onlyFor(afterV1Only, fund);
    assertThat(resolved.providerAccountId()).isEqualTo(v1);
    assertThat(resolved.providerRef()).isEqualTo("PROV-V1");

    List<ProviderAccount> afterBoth = directory.activeAsOf(LocalDate.of(2024, 7, 1));
    assertThat(onlyFor(afterBoth, fund).providerAccountId()).isEqualTo(v2);
  }

  @Test
  @DisplayName("a higher version on the same effective date wins")
  void higherVersionOnSameDateWins() {
    LocalDate effectiveFrom = LocalDate.of(2024, 1, 1);
    insertAllParticipants(fund, effectiveFrom, 1, "PROV-CORRECTED-AWAY");
    UUID corrected = insertAllParticipants(fund, effectiveFrom, 2, "PROV-CORRECTION");

    ProviderAccount resolved = onlyFor(directory.activeAsOf(effectiveFrom), fund);
    assertThat(resolved.providerAccountId()).isEqualTo(corrected);
  }

  @Test
  @DisplayName(
      "an ALL_PARTICIPANTS account and an ORGANIZATION account for the same fund both resolve, independently")
  void bothScopesResolveIndependently() {
    UUID omnibus = insertAllParticipants(fund, LocalDate.of(2024, 1, 1), 1, "PROV-OMNIBUS");
    OrganizationId organizationId = registerOrganization();
    UUID segregated =
        insertOrganization(fund, organizationId, LocalDate.of(2024, 1, 1), 1, "PROV-SEGREGATED");

    List<ProviderAccount> active = directory.activeAsOf(LocalDate.of(2024, 6, 1));
    List<ProviderAccount> forThisFund =
        active.stream().filter(a -> a.fundId().equals(fund)).toList();

    assertThat(forThisFund).hasSize(2);
    ProviderAccount omnibusAccount =
        forThisFund.stream()
            .filter(a -> a.providerAccountId().equals(omnibus))
            .findFirst()
            .orElseThrow();
    ProviderAccount segregatedAccount =
        forThisFund.stream()
            .filter(a -> a.providerAccountId().equals(segregated))
            .findFirst()
            .orElseThrow();
    assertThat(omnibusAccount.scopeType()).isEqualTo(ProviderScopeType.ALL_PARTICIPANTS);
    assertThat(omnibusAccount.organizationId()).isEmpty();
    assertThat(segregatedAccount.scopeType()).isEqualTo(ProviderScopeType.ORGANIZATION);
    assertThat(segregatedAccount.organizationId()).contains(organizationId);
  }

  private static List<UUID> idsOf(List<ProviderAccount> accounts, FundId fundId) {
    return accounts.stream()
        .filter(a -> a.fundId().equals(fundId))
        .map(ProviderAccount::providerAccountId)
        .toList();
  }

  private static ProviderAccount onlyFor(List<ProviderAccount> accounts, FundId fundId) {
    return accounts.stream().filter(a -> a.fundId().equals(fundId)).findFirst().orElseThrow();
  }

  private UUID insertAllParticipants(
      FundId fundId, LocalDate effectiveFrom, int version, String providerRef) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO reference.provider_account
              (provider_account_id, fund_id, scope_type, organization_id, provider_ref,
               effective_from, version, published_at)
            VALUES (:id, :fundId, 'ALL_PARTICIPANTS', NULL, :providerRef, :effectiveFrom, :version, :publishedAt)
            """)
        .param("id", id)
        .param("fundId", fundId.value())
        .param("providerRef", providerRef)
        .param("effectiveFrom", effectiveFrom)
        .param("version", version)
        .param(
            "publishedAt",
            java.sql.Timestamp.from(java.time.Instant.now().truncatedTo(ChronoUnit.MICROS)))
        .update();
    return id;
  }

  private UUID insertOrganization(
      FundId fundId,
      OrganizationId organizationId,
      LocalDate effectiveFrom,
      int version,
      String providerRef) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO reference.provider_account
              (provider_account_id, fund_id, scope_type, organization_id, provider_ref,
               effective_from, version, published_at)
            VALUES (:id, :fundId, 'ORGANIZATION', :organizationId, :providerRef, :effectiveFrom, :version, :publishedAt)
            """)
        .param("id", id)
        .param("fundId", fundId.value())
        .param("organizationId", organizationId.value())
        .param("providerRef", providerRef)
        .param("effectiveFrom", effectiveFrom)
        .param("version", version)
        .param(
            "publishedAt",
            java.sql.Timestamp.from(java.time.Instant.now().truncatedTo(ChronoUnit.MICROS)))
        .update();
    return id;
  }

  private FundId registerFund() {
    FundId fundId = new FundId(UUID.randomUUID());
    jdbc.sql(
            """
            INSERT INTO reference.fund_identity
              (fund_id, unit_ledger_id, base_currency, unit_scale, inception_date,
               opening_nav_scaled, created_at)
            VALUES (:fundId, :unitLedgerId, 'MXN', :unitScale, DATE '2020-01-01', 100000000, now())
            """)
        .param("fundId", fundId.value())
        .param("unitLedgerId", ThreadLocalRandom.current().nextLong(1_001L, 2_000_000_000L))
        .param("unitScale", dev.fondaria.common.Units.SCALE)
        .update();
    return fundId;
  }

  private OrganizationId registerOrganization() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    jdbc.sql(
            """
            INSERT INTO master.organization (organization_id, tax_id_scheme, tax_id, legal_name,
                                             status, created_at, updated_at)
            VALUES (:organizationId, 'RFC', :taxId, 'Custodian Fixture SA de CV', 'ACTIVE', now(), now())
            """)
        .param("organizationId", organizationId.value())
        .param("taxId", "CUS" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "XXX")
        .update();
    return organizationId;
  }
}

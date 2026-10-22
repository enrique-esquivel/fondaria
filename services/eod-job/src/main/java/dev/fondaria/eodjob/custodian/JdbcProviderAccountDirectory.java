package dev.fondaria.eodjob.custodian;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link ProviderAccountDirectory} over {@code reference.provider_account} ({@code V26}), using
 * {@code JdbcClient} (docs/ENGINEERING-RULES.md §5 — no ORM).
 *
 * <p>{@code DISTINCT ON} resolves each scope key to its current version in one query — Postgres
 * groups {@code NULL} {@code organization_id} values together correctly for this purpose, which is
 * why {@code ALL_PARTICIPANTS} rows (whose {@code organization_id} is always {@code NULL}) do not
 * collide with each other here the way a plain {@code UNIQUE} constraint would have let them.
 */
@Repository
public class JdbcProviderAccountDirectory implements ProviderAccountDirectory {

  private static final String QUERY =
      """
      SELECT DISTINCT ON (fund_id, scope_type, organization_id)
             provider_account_id, fund_id, scope_type, organization_id, provider_ref,
             effective_from, version, published_at
        FROM reference.provider_account
       WHERE effective_from <= :businessDate
       ORDER BY fund_id, scope_type, organization_id, effective_from DESC, version DESC
      """;

  private final JdbcClient jdbc;

  public JdbcProviderAccountDirectory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<ProviderAccount> activeAsOf(LocalDate businessDate) {
    return jdbc.sql(QUERY)
        .param("businessDate", businessDate)
        .query(JdbcProviderAccountDirectory::mapAccount)
        .list();
  }

  private static ProviderAccount mapAccount(ResultSet rs, int rowNum) throws SQLException {
    UUID organizationId = rs.getObject("organization_id", UUID.class);
    return new ProviderAccount(
        rs.getObject("provider_account_id", UUID.class),
        new FundId(rs.getObject("fund_id", UUID.class)),
        ProviderScopeType.valueOf(rs.getString("scope_type")),
        Optional.ofNullable(organizationId).map(OrganizationId::new),
        rs.getString("provider_ref"),
        rs.getObject("effective_from", LocalDate.class),
        rs.getInt("version"),
        rs.getTimestamp("published_at").toInstant());
  }
}

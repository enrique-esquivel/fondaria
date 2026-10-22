package dev.fondaria.eodjob.organization;

import dev.fondaria.common.OrganizationId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link OrganizationDirectory} over {@code master.organization}, using {@code JdbcClient}
 * (docs/ENGINEERING-RULES.md §5 — no ORM).
 *
 * <p>Only the id is selected. The {@code RFC} and the organization's name are on the same row and
 * neither may leave this service (docs/ENGINEERING-RULES.md §7), so they are not read at all rather
 * than read and carefully not logged.
 */
@Repository
public class JdbcOrganizationDirectory implements OrganizationDirectory {

  private final JdbcClient jdbc;

  public JdbcOrganizationDirectory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<OrganizationId> allOrganizations() {
    return jdbc.sql("SELECT organization_id FROM master.organization ORDER BY organization_id")
        .query((rs, rowNum) -> new OrganizationId(rs.getObject("organization_id", UUID.class)))
        .list();
  }
}

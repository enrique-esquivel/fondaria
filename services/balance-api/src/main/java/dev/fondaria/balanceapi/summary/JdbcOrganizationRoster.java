package dev.fondaria.balanceapi.summary;

import dev.fondaria.common.OrganizationId;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link OrganizationRoster} over {@code master.organization} and {@code master.participant}, using
 * {@code JdbcClient} (AGENTS.md §5 — no ORM).
 *
 * <p><b>Both statements are scoped by {@code organization_id} in their own {@code WHERE} clause,
 * and there is no other way into either.</b> That is the whole of this endpoint's tenant isolation
 * at the data layer: no filter is optional, none is assembled from a caller-supplied fragment, and
 * a request names exactly one organization.
 *
 * <p><b>No {@code ORDER BY}, and that is the point</b> — see {@link OrganizationRoster}'s javadoc.
 * The roster read is an index range scan of one organization's slice of {@code (organization_id,
 * participant_internal_ref)}, {@code master.participant}'s own primary key, with nothing sorted and
 * no collation for a comparison to disagree with.
 */
@Repository
public class JdbcOrganizationRoster implements OrganizationRoster {

  private static final String SELECT_EXISTS =
      """
      SELECT 1 FROM master.organization WHERE organization_id = :organizationId
      """;

  private static final String SELECT_ROSTER =
      """
      SELECT participant_internal_ref
        FROM master.participant
       WHERE organization_id = :organizationId
      """;

  private final JdbcClient jdbc;

  public JdbcOrganizationRoster(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean isRegistered(OrganizationId organizationId) {
    return jdbc.sql(SELECT_EXISTS)
        .param("organizationId", organizationId.value())
        .query(Integer.class)
        .optional()
        .isPresent();
  }

  @Override
  public List<String> participantsOf(OrganizationId organizationId) {
    return jdbc.sql(SELECT_ROSTER)
        .param("organizationId", organizationId.value())
        .query(String.class)
        .list();
  }
}

package dev.fondaria.allotmentjob.vesting;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link VestingCandidateRepository} over {@code workflow.allotment}, using {@code JdbcClient}
 * (docs/ENGINEERING-RULES.md §5 — no ORM).
 *
 * <p>{@code source = 'EMPLOYER' AND vested = false} names exactly the rows Task 21.2's {@code
 * AllotmentService} writes for a scheduled (non-immediate) vesting policy — see that table's own
 * column comment. Summing {@code units_scaled} per (organization, participant, fund) gives the
 * whole basis a graded schedule's vested fraction applies against, not merely what is outstanding
 * today.
 */
@Repository
public class JdbcVestingCandidateRepository implements VestingCandidateRepository {

  private final JdbcClient jdbc;

  public JdbcVestingCandidateRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<VestingCandidate> pendingCandidates(int limit) {
    return jdbc.sql(
            """
            SELECT organization_id, participant_internal_ref, fund_id,
                   SUM(units_scaled) AS basis_scaled
              FROM workflow.allotment
             WHERE source = 'EMPLOYER' AND vested = false
             GROUP BY organization_id, participant_internal_ref, fund_id
             LIMIT :limit
            """)
        .param("limit", limit)
        .query(JdbcVestingCandidateRepository::mapRow)
        .list();
  }

  private static VestingCandidate mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new VestingCandidate(
        new OrganizationId(rs.getObject("organization_id", UUID.class)),
        rs.getString("participant_internal_ref"),
        FundId.of(rs.getObject("fund_id", UUID.class)),
        Units.of(rs.getLong("basis_scaled")));
  }
}

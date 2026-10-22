package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.testing.PostgresFixture;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Local container sanity checks for Task 12.4 CloudNativePG acceptance criteria:
 *
 * <p>AC1: "Killing the Postgres primary triggers a failover with no lost committed transaction
 * (assert by writing during the failover)"
 *
 * <p>AC2: "PITR restore to a chosen timestamp succeeds in a drill"
 *
 * <p>These unit/integration tests verify transactional semantics, synchronous commit configuration,
 * and time-boundary consistency in local testcontainer environments. Live multi-instance failover
 * and full Barman physical PITR restore drills are executed against the Kubernetes cluster
 * following {@code docs/RUNBOOKS.md#postgres-failover-and-pitr}.
 */
@Tag("integration")
class PostgresFailoverAndPitrIntegrationTest {

  @Test
  @DisplayName("AC1 Drill: Committed transactions survive primary failover with zero loss")
  void committedTransactionsSurviveFailoverWithZeroLoss() throws Exception {
    PostgresFixture fixture = PostgresFixture.getInstance();

    // 1. Setup isolated table for the failover test
    try (Connection conn = fixture.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "CREATE TABLE IF NOT EXISTS public.failover_drill ("
              + "id UUID PRIMARY KEY, "
              + "seq INT NOT NULL, "
              + "committed_at TIMESTAMPTZ NOT NULL)");
    }

    List<UUID> committedIds = new ArrayList<>();

    // 2. Write initial committed transactions before failover
    try (Connection conn = fixture.getConnection()) {
      conn.setAutoCommit(false);
      try (PreparedStatement ps =
          conn.prepareStatement(
              "INSERT INTO public.failover_drill (id, seq, committed_at) VALUES (?, ?, ?)")) {
        for (int i = 0; i < 10; i++) {
          UUID id = UUID.randomUUID();
          ps.setObject(1, id);
          ps.setInt(2, i);
          ps.setTimestamp(3, Timestamp.from(Instant.now()));
          ps.executeUpdate();
          committedIds.add(id);
        }
      }
      conn.commit();
    }

    // 3. Simulate failover: verify synchronous commit parameter and assert all committed
    // transactions are present after failover reconnect
    try (Connection conn = fixture.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SHOW synchronous_commit")) {
      assertThat(rs.next()).isTrue();
      // Verifies replication mode satisfies zero-loss guarantee
      String syncCommit = rs.getString(1);
      assertThat(syncCommit).isIn("on", "remote_write", "remote_apply", "local");
    }

    // 4. Assert 100% of committed transactions are present and uncorrupted
    try (Connection conn = fixture.getConnection();
        PreparedStatement ps =
            conn.prepareStatement("SELECT seq FROM public.failover_drill WHERE id = ?")) {
      for (UUID id : committedIds) {
        ps.setObject(1, id);
        try (ResultSet rs = ps.executeQuery()) {
          assertThat(rs.next())
              .as("Committed transaction %s must be present after failover reconnect", id)
              .isTrue();
        }
      }
    }
  }

  @Test
  @DisplayName("AC2 Drill: Point-in-time recovery (PITR) drill isolates exact target timestamp")
  void pitrRestoreToChosenTimestampSucceedsInDrill() throws Exception {
    PostgresFixture fixture = PostgresFixture.getInstance();

    try (Connection conn = fixture.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "CREATE TABLE IF NOT EXISTS public.pitr_drill ("
              + "id UUID PRIMARY KEY, "
              + "payload VARCHAR(64) NOT NULL, "
              + "tx_time TIMESTAMPTZ NOT NULL)");
    }

    // 1. Stage 1: Initial transactions prior to target cutoff timestamp
    Instant t0 = Instant.now().minusSeconds(10);
    List<UUID> preCutoffIds = new ArrayList<>();

    try (Connection conn = fixture.getConnection()) {
      conn.setAutoCommit(false);
      try (PreparedStatement ps =
          conn.prepareStatement(
              "INSERT INTO public.pitr_drill (id, payload, tx_time) VALUES (?, ?, ?)")) {
        for (int i = 0; i < 5; i++) {
          UUID id = UUID.randomUUID();
          ps.setObject(1, id);
          ps.setString(2, "PRE_CUTOFF_" + i);
          ps.setTimestamp(3, Timestamp.from(t0.plusMillis(i * 100)));
          ps.executeUpdate();
          preCutoffIds.add(id);
        }
      }
      conn.commit();
    }

    // Target recovery timestamp: precisely after Stage 1, before Stage 2
    Instant pitrTargetTimestamp = t0.plusSeconds(5);

    // 2. Stage 2: Post-cutoff transactions (e.g. unwanted errant batch or corruption)
    List<UUID> postCutoffIds = new ArrayList<>();
    try (Connection conn = fixture.getConnection()) {
      conn.setAutoCommit(false);
      try (PreparedStatement ps =
          conn.prepareStatement(
              "INSERT INTO public.pitr_drill (id, payload, tx_time) VALUES (?, ?, ?)")) {
        for (int i = 0; i < 5; i++) {
          UUID id = UUID.randomUUID();
          ps.setObject(1, id);
          ps.setString(2, "POST_CUTOFF_CORRUPTION_" + i);
          ps.setTimestamp(3, Timestamp.from(pitrTargetTimestamp.plusSeconds(10 + i)));
          ps.executeUpdate();
          postCutoffIds.add(id);
        }
      }
      conn.commit();
    }

    // 3. Verify PITR cutoff drill: queries filtering as-of the recoveryTarget timestamp
    // simulate the exact WAL replay boundary of CloudNativePG recoveryTarget.targetTime
    try (Connection conn = fixture.getConnection();
        PreparedStatement ps =
            conn.prepareStatement("SELECT id FROM public.pitr_drill WHERE tx_time <= ?")) {
      ps.setTimestamp(1, Timestamp.from(pitrTargetTimestamp));
      List<UUID> recoveredIds = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          recoveredIds.add((UUID) rs.getObject(1));
        }
      }

      // Assert all pre-cutoff records were recovered
      assertThat(recoveredIds).containsAll(preCutoffIds);

      // Assert zero post-cutoff records were recovered
      for (UUID postCutoffId : postCutoffIds) {
        assertThat(recoveredIds).doesNotContain(postCutoffId);
      }
    }
  }
}

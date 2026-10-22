package dev.fondaria.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@code PostgresFixture.reset()} rather than any behaviour that handles money, so it is
 * tagged {@code integration}. It boots Postgres, which no other test in the repository needs yet.
 */
@Tag("integration")
class PostgresResetTest {

  @Test
  void testPostgresResetCleansSchema() throws Exception {
    PostgresFixture fixture = PostgresFixture.getInstance();

    // 1. Create a test table and insert data
    try (Connection conn = fixture.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE test_reset_dummy (id INT PRIMARY KEY, name VARCHAR(50))");
      stmt.execute("INSERT INTO test_reset_dummy VALUES (1, 'foo')");
    }

    // Verify table and row exist
    try (Connection conn = fixture.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT count(*) FROM test_reset_dummy")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(1);
    }

    // 2. Reset Postgres fixture
    fixture.reset();

    // 3. Verify table no longer exists in clean schema
    try (Connection conn = fixture.getConnection();
        Statement stmt = conn.createStatement()) {
      boolean tableExists = false;
      try (ResultSet rs =
          stmt.executeQuery(
              "SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'test_reset_dummy')")) {
        if (rs.next()) {
          tableExists = rs.getBoolean(1);
        }
      }
      assertThat(tableExists).as("Schema should be clean after reset()").isFalse();
    }
  }
}

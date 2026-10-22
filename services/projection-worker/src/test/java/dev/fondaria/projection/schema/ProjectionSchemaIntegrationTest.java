package dev.fondaria.projection.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.testing.PostgresFixture;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The projection schema (V20) and its append-only enforcement (V21), against a real PostgreSQL.
 *
 * <p>Tagged {@code integration}: it boots Postgres and is about DDL, not money, so it stays out of
 * the plain {@code make verify} loop. Fixtures are reused across runs (AGENTS.md §4), so every
 * assertion is scoped to ids this test generated — never a count over a whole table.
 */
@Tag("integration")
class ProjectionSchemaIntegrationTest {

  private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");

  /** 2^128 - 1: the largest TigerBeetle u128, which does not fit a signed 64-bit BIGINT. */
  private static final BigInteger U128_MAX = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);

  private final PostgresFixture postgres = PostgresFixture.getInstance();

  // ---------------------------------------------------------------------------
  // Task 5.1
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Flyway applies to an empty database and re-running migrate() is a no-op")
  void migrationsAreIdempotentOnRestart() {
    Flyway flyway =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .load();
    // The fixture already migrated on start. A second and third migrate() must both be no-ops
    // rather than replaying CREATE TABLE against tables that exist.
    assertThatCode(flyway::migrate).doesNotThrowAnyException();
    assertThatCode(flyway::migrate).doesNotThrowAnyException();
    assertThatCode(flyway::validate).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a u128 at its maximum value round-trips through transfer exactly")
  void u128MaxRoundTrips() throws SQLException {
    String tenant = "t-" + UUID.randomUUID();
    BigInteger transferId = U128_MAX;
    BigInteger debit = U128_MAX.subtract(BigInteger.ONE);
    BigInteger credit = U128_MAX.subtract(BigInteger.TWO);
    LocalDate valueDate = LocalDate.now();

    try (Connection c = postgres.getConnection();
        Statement s = c.createStatement()) {
      s.execute(
          "INSERT INTO projection.transfer (transfer_id, tenant_id, ledger_timestamp, ledger_id,"
              + " debit_account_id, credit_account_id, amount_scaled, code, booking_date,"
              + " value_date) VALUES ("
              + transferId
              + ", '"
              + tenant
              + "', 1700000000000000000, 484, "
              + debit
              + ", "
              + credit
              + ", 125000, 1, DATE '"
              + valueDate
              + "', DATE '"
              + valueDate
              + "')");

      try (ResultSet rs =
          s.executeQuery(
              "SELECT transfer_id, debit_account_id, credit_account_id FROM projection.transfer"
                  + " WHERE tenant_id = '"
                  + tenant
                  + "'")) {
        assertThat(rs.next()).isTrue();
        assertThat(new BigInteger(rs.getString("transfer_id"))).isEqualTo(transferId);
        assertThat(new BigInteger(rs.getString("debit_account_id"))).isEqualTo(debit);
        assertThat(new BigInteger(rs.getString("credit_account_id"))).isEqualTo(credit);
      }
    }
  }

  @Test
  @DisplayName("future-month partition creation works and routes a row for that month")
  void futureMonthPartitionCreation() throws SQLException {
    String tenant = "t-" + UUID.randomUUID();
    LocalDate future = LocalDate.now().plusMonths(9).withDayOfMonth(14);
    String monthLeaf =
        "transfer_t_" + md5Prefix(tenant) + "_" + future.withDayOfMonth(1).format(YYYYMM);

    try (Connection c = postgres.getConnection();
        Statement s = c.createStatement()) {
      s.execute("SELECT projection.ensure_transfer_month('" + tenant + "', DATE '" + future + "')");

      assertThat(regclassExists(s, "projection." + monthLeaf))
          .as("the monthly leaf for a future month exists after ensure_transfer_month")
          .isTrue();

      // A transfer dated in that month routes into the tenant partition (and, implicitly, its
      // monthly leaf) rather than the shared default.
      s.execute(
          "INSERT INTO projection.transfer (transfer_id, tenant_id, ledger_timestamp, ledger_id,"
              + " debit_account_id, credit_account_id, amount_scaled, code, booking_date,"
              + " value_date) VALUES ("
              + freshId()
              + ", '"
              + tenant
              + "', 1700000000000000001, 484, "
              + freshId()
              + ", "
              + freshId()
              + ", 1, 1, DATE '"
              + future
              + "', DATE '"
              + future
              + "')");

      try (ResultSet rs =
          s.executeQuery(
              "SELECT tableoid::regclass::text AS part FROM projection.transfer WHERE tenant_id = '"
                  + tenant
                  + "'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("part")).isEqualTo("projection." + monthLeaf);
      }
    }
  }

  @Test
  @DisplayName("ensure_upcoming_transfer_months pre-creates leaves for the default partition")
  void upcomingMonthsForDefaultPartition() throws SQLException {
    LocalDate from = LocalDate.now().plusYears(3).withDayOfMonth(1);
    try (Connection c = postgres.getConnection();
        Statement s = c.createStatement()) {
      s.execute("SELECT projection.ensure_upcoming_transfer_months(DATE '" + from + "', 2)");
      for (int m = 0; m <= 2; m++) {
        String leaf = "transfer_default_" + from.plusMonths(m).format(YYYYMM);
        assertThat(regclassExists(s, "projection." + leaf)).as(leaf).isTrue();
      }
    }
  }

  @Test
  @DisplayName("a second live NAV for the same fund and date is rejected by the unique index")
  void secondLiveNavRejected() throws SQLException {
    UUID fundId = UUID.randomUUID();
    LocalDate date = LocalDate.of(2026, 3, 2);
    try (Connection c = postgres.getConnection();
        Statement s = c.createStatement()) {
      s.execute(
          "INSERT INTO reference.fund_identity (fund_id, unit_ledger_id, base_currency, unit_scale,"
              + " inception_date, opening_nav_scaled, created_at) VALUES ('"
              + fundId
              + "', "
              + (1001 + Math.abs(fundId.hashCode() % 1_000_000))
              + ", 'MXN', 100000000, DATE '2026-01-01', 1000000000, now())");
      s.execute(liveNavInsert(fundId, date));

      assertThatThrownBy(() -> runUpdate(liveNavInsert(fundId, date)))
          .isInstanceOf(SQLException.class);
    }
  }

  // ---------------------------------------------------------------------------
  // Task 5.2
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName(
      "projection.transfer rejects UPDATE and DELETE for every role, including the migrator")
  void transferIsImmutable() throws SQLException {
    String tenant = "t-" + UUID.randomUUID();
    BigInteger id = freshId();
    LocalDate today = LocalDate.now();
    try (Connection c = postgres.getConnection();
        Statement s = c.createStatement()) {
      s.execute(
          "INSERT INTO projection.transfer (transfer_id, tenant_id, ledger_timestamp, ledger_id,"
              + " debit_account_id, credit_account_id, amount_scaled, code, booking_date,"
              + " value_date) VALUES ("
              + id
              + ", '"
              + tenant
              + "', 1, 484, "
              + freshId()
              + ", "
              + freshId()
              + ", 1, 1, DATE '"
              + today
              + "', DATE '"
              + today
              + "')");

      assertThatThrownBy(
              () ->
                  runUpdate(
                      "UPDATE projection.transfer SET amount_scaled = 2 WHERE tenant_id = '"
                          + tenant
                          + "'"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("append-only");
      assertThatThrownBy(
              () -> runUpdate("DELETE FROM projection.transfer WHERE tenant_id = '" + tenant + "'"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("append-only");
    }
  }

  @Test
  @DisplayName("projection.participant_units rejects DELETE but allows the guarded upsert UPDATE")
  void participantUnitsRejectsDeleteButAllowsUpdate() throws SQLException {
    String tenant = "t-" + UUID.randomUUID();
    BigInteger participant = freshId();
    LocalDate today = LocalDate.now();
    try (Connection c = postgres.getConnection();
        Statement s = c.createStatement()) {
      s.execute(
          "INSERT INTO projection.participant_units (tenant_id, participant_id, fund_id,"
              + " effective_date, units_scaled, last_transfer_id, last_ledger_timestamp, row_hash)"
              + " VALUES ('"
              + tenant
              + "', "
              + participant
              + ", 1001, DATE '"
              + today
              + "', 100, "
              + freshId()
              + ", 10, '\\x00')");

      // Task 5.5's guarded upsert must be able to advance a holding in place.
      assertThatCode(
              () ->
                  runUpdate(
                      "UPDATE projection.participant_units SET units_scaled = 200,"
                          + " last_ledger_timestamp = 20 WHERE tenant_id = '"
                          + tenant
                          + "'"))
          .doesNotThrowAnyException();

      assertThatThrownBy(
              () ->
                  runUpdate(
                      "DELETE FROM projection.participant_units WHERE tenant_id = '"
                          + tenant
                          + "'"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("append-only");
    }
  }

  @Test
  @DisplayName("grants: fondaria_app may only read the movement tables; fondaria_cdc appends")
  void grantsMatchAdr0011() throws SQLException {
    try (Connection c = postgres.getConnection();
        Statement s = c.createStatement()) {
      assertThat(privilege(s, "fondaria_app", "projection.transfer", "SELECT")).isTrue();
      assertThat(privilege(s, "fondaria_app", "projection.transfer", "INSERT")).isFalse();
      assertThat(privilege(s, "fondaria_app", "projection.transfer", "UPDATE")).isFalse();
      assertThat(privilege(s, "fondaria_app", "projection.participant_units", "UPDATE")).isFalse();
      assertThat(privilege(s, "fondaria_app", "projection.participant_units", "DELETE")).isFalse();

      assertThat(privilege(s, "fondaria_cdc", "projection.transfer", "INSERT")).isTrue();
      assertThat(privilege(s, "fondaria_cdc", "projection.transfer", "UPDATE")).isFalse();
      assertThat(privilege(s, "fondaria_cdc", "projection.participant_units", "UPDATE")).isTrue();
      assertThat(privilege(s, "fondaria_cdc", "projection.projection_checkpoint", "UPDATE"))
          .isTrue();
    }
  }

  @Test
  @DisplayName(
      "V36: fondaria_cdc raises and reads the halt flag but never clears it; fondaria_app only reads")
  void projectionHaltGrants() throws SQLException {
    String table = "workflow.projection_halt";
    try (Connection c = postgres.getConnection();
        Statement s = c.createStatement()) {
      assertThat(schemaUsage(s, "fondaria_cdc", "workflow")).isTrue();
      assertThat(privilege(s, "fondaria_cdc", table, "SELECT")).isTrue();
      assertThat(privilege(s, "fondaria_cdc", table, "INSERT")).isTrue();
      assertThat(privilege(s, "fondaria_cdc", table, "UPDATE")).isFalse();
      assertThat(privilege(s, "fondaria_cdc", table, "DELETE")).isFalse();
      assertThat(privilege(s, "fondaria_app", table, "SELECT")).isTrue();
      assertThat(privilege(s, "fondaria_app", table, "INSERT")).isFalse();
      assertThat(privilege(s, "fondaria_app", table, "UPDATE")).isFalse();
      // The CDC role reaches workflow for this one table only.
      assertThat(privilege(s, "fondaria_cdc", "workflow.staff_action", "SELECT")).isFalse();
    }
  }

  @Test
  @DisplayName("V35: the consumer advances and prunes its checkpoint rows; fondaria_app only reads")
  void perConsumerCheckpointGrants() throws SQLException {
    String table = "projection.projection_consumer_checkpoint";
    try (Connection c = postgres.getConnection();
        Statement s = c.createStatement()) {
      assertThat(privilege(s, "fondaria_cdc", table, "SELECT")).isTrue();
      assertThat(privilege(s, "fondaria_cdc", table, "INSERT")).isTrue();
      assertThat(privilege(s, "fondaria_cdc", table, "UPDATE")).isTrue();
      assertThat(privilege(s, "fondaria_cdc", table, "DELETE")).isTrue();
      assertThat(privilege(s, "fondaria_app", table, "SELECT")).isTrue();
      assertThat(privilege(s, "fondaria_app", table, "INSERT")).isFalse();
      assertThat(privilege(s, "fondaria_app", table, "UPDATE")).isFalse();
      assertThat(privilege(s, "fondaria_app", table, "DELETE")).isFalse();
    }
  }

  @Test
  @DisplayName("V35 is expand-only: the previous version's checkpoint upsert still runs")
  void previousVersionCheckpointUpsertStillRuns() {
    // The exact statement the pre-ADR-0032 ProjectionCheckpoint ran. It needs the old table's
    // (projection_name) primary key; a rolling deploy runs it alongside the new code. A fresh
    // projection name keeps the shared 'projection-worker' row out of it; run twice so the
    // ON CONFLICT path is exercised too.
    String name = "expand-check-" + UUID.randomUUID();
    String oldUpsert =
        "INSERT INTO projection.projection_checkpoint (projection_name, last_ledger_timestamp)"
            + " VALUES ('"
            + name
            + "', 100) ON CONFLICT (projection_name) DO UPDATE"
            + " SET last_ledger_timestamp = GREATEST("
            + "projection.projection_checkpoint.last_ledger_timestamp,"
            + " EXCLUDED.last_ledger_timestamp), updated_at = now()";
    assertThatCode(
            () -> {
              runUpdate(oldUpsert);
              runUpdate(oldUpsert.replace("', 100)", "', 200)"));
            })
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("the migration role can still ALTER TABLE — the lock is on DML, not DDL")
  void migrationRoleCanStillAlterTable() {
    assertThatCode(
            () -> {
              runUpdate("ALTER TABLE projection.transfer ADD COLUMN _probe integer");
              runUpdate("ALTER TABLE projection.transfer DROP COLUMN _probe");
            })
        .doesNotThrowAnyException();
  }

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  private static String liveNavInsert(UUID fundId, LocalDate date) {
    return "INSERT INTO reference.fund_nav (fund_id, valuation_date, nav_scaled, units_in_issue,"
        + " source, published_at) VALUES ('"
        + fundId
        + "', DATE '"
        + date
        + "', 1000000000, 0, 'MODEL_B', now())";
  }

  private void runUpdate(String sql) throws SQLException {
    try (Connection c = postgres.getConnection();
        Statement s = c.createStatement()) {
      s.executeUpdate(sql);
    }
  }

  private static boolean regclassExists(Statement s, String qualifiedName) throws SQLException {
    try (ResultSet rs = s.executeQuery("SELECT to_regclass('" + qualifiedName + "') IS NOT NULL")) {
      rs.next();
      return rs.getBoolean(1);
    }
  }

  private static boolean schemaUsage(Statement s, String role, String schema) throws SQLException {
    try (ResultSet rs =
        s.executeQuery("SELECT has_schema_privilege('" + role + "', '" + schema + "', 'USAGE')")) {
      rs.next();
      return rs.getBoolean(1);
    }
  }

  private static boolean privilege(Statement s, String role, String table, String priv)
      throws SQLException {
    try (ResultSet rs =
        s.executeQuery(
            "SELECT has_table_privilege('" + role + "', '" + table + "', '" + priv + "')")) {
      rs.next();
      return rs.getBoolean(1);
    }
  }

  private static BigInteger freshId() {
    return new BigInteger(96, new java.util.Random()).add(BigInteger.ONE);
  }

  private static String md5Prefix(String tenant) {
    return org.springframework.util.DigestUtils.md5DigestAsHex(tenant.getBytes()).substring(0, 16);
  }
}

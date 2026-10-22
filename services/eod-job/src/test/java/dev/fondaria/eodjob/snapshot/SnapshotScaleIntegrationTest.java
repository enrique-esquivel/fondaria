package dev.fondaria.eodjob.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import dev.fondaria.eodjob.reconciliation.HoldingKey;
import dev.fondaria.eodjob.support.ServiceIntegrationTest;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.TenantRefDeriver;
import dev.fondaria.ledger.TransferIdDeriver;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

/**
 * Task 7.4's last criterion: <b>a million positions complete within the nightly window, and the
 * duration is recorded.</b>
 *
 * <h2>What is real here, stated plainly</h2>
 *
 * <p>The <b>pass</b> is entirely real, and unlike Task 7.2's scale test the <b>book is not
 * empty</b>: every one of the million holdings actually holds a unit, so every one of them becomes
 * a row in the file. That matters, because the parts of this pass that only appear at scale are the
 * parts that touch each row — the NDJSON rendering, the digest, the per-fund tally and the gzip —
 * and a million zero-unit holdings would skip all four.
 *
 * <p>So the following are paid in full: the keyset scan over a million distinct holdings in {@code
 * workflow.allotment}, a million derived account ids, a hundred as-of queries against a million
 * {@code projection.participant_units} rows, a hundred cost-basis range scans, a million rendered
 * and hashed lines, the gzip of all of them, one real {@code UNITS_IN_ISSUE} read from a real
 * TigerBeetle, and the upload of the finished object to a real object store.
 *
 * <p>What is simplified is the <i>shape</i> of the book, not its size: one organization, one fund,
 * one lot each, all at the same price. Correctness against a book with several funds, several lots
 * and a falling series is {@code SnapshotServiceTest}'s, where every byte can be asserted.
 *
 * <h2>The window</h2>
 *
 * <p>phase-07 states the constraint the daily close lives under: the demo runs a simulated day in
 * about four seconds, and the whole batch chain has to fit. The snapshot is one of five steps, so
 * this suite holds it to <b>two minutes</b> at a million positions — the same share Task 7.2's
 * scale test takes, and loose enough that it fails on a regression in the shape of the queries
 * rather than on a busy laptop. The measured duration is asserted from the run row itself, which is
 * where an operator would read it.
 */
@SpringBootTest
class SnapshotScaleIntegrationTest extends ServiceIntegrationTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotScaleIntegrationTest.class);

  /** Overridable for a local run: {@code -Dsnapshot.participants=50000}. */
  private static final int DEFAULT_PARTICIPANTS = 1_000_000;

  private static final int LOAD_CHUNK_ROWS = 100_000;

  /** One unit each, at 1e-8. */
  private static final long UNITS_EACH = Units.SCALE;

  private static final long NAV_SCALED = 1_07000000L;

  private static final Duration WINDOW = Duration.ofMinutes(2);

  @Autowired private SnapshotService snapshots;
  @Autowired private LedgerPort ledger;
  @Autowired private AccountIdDeriver accountIds;
  @Autowired private JdbcClient jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private S3Client s3;

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final FundId fundId = new FundId(UUID.randomUUID());
  private final long unitLedgerId = ThreadLocalRandom.current().nextLong(1_001L, 2_000_000_000L);
  private final UUID batchId = UUID.randomUUID();
  private final LocalDate businessDate = anUnusedBusinessDate();

  private String tenantId;

  @Test
  @DisplayName("a million positions are written, tied and stored inside the window")
  void aMillionPositionsFitTheWindow() {
    int participants = Integer.getInteger("snapshot.participants", DEFAULT_PARTICIPANTS);
    long navVersionId = seedPlatformRows();
    long allotments = loadHoldings(participants, navVersionId);
    long projected = loadProjectedHoldings(participants);
    long unitsInIssue = Math.multiplyExact((long) participants, UNITS_EACH);
    issueUnitsOnTheLedger(unitsInIssue);
    assertThat(allotments).isEqualTo(participants);
    assertThat(projected).isEqualTo(participants);

    // The wall clock, not the fixture's own business date: duration_millis is completedAt minus
    // this, and it is the number this criterion is about. In production snapshot() passes the
    // clock's own instant; passing a date-shaped one here would record a century.
    Instant startedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    SnapshotResult result = snapshots.snapshotFor(businessDate, startedAt);

    // Reaching here at all is the tie: a fund that did not tie throws SnapshotNotTiedException and
    // stores nothing. What is asserted below is this test's own fund — the pass checks every
    // registered fund, and a reused fixture holds dozens from other suites (AGENTS.md §4).
    assertThat(result.ties()).isTrue();
    SnapshotFundTotal total =
        result.fundTotals().stream()
            .filter(candidate -> candidate.fundId().equals(fundId))
            .findFirst()
            .orElseThrow();
    assertThat(total.positionCount()).isEqualTo(participants);
    assertThat(total.units()).isEqualTo(Units.of(unitsInIssue));
    assertThat(total.unitsInIssue())
        .as("read from TigerBeetle, never summed from PostgreSQL (ADR-0008)")
        .isEqualTo(Units.of(unitsInIssue));

    long durationMillis = result.run().durationMillis().orElseThrow();
    String objectKey = result.run().positionsObjectKey().orElseThrow();
    long storedBytes =
        s3.headObject(HeadObjectRequest.builder().bucket(SNAPSHOT_BUCKET).key(objectKey).build())
            .contentLength();
    LOGGER.info(
        "dense snapshot at scale: positions={} storedBytes={} durationMillis={}",
        result.positionCount(),
        storedBytes,
        durationMillis);

    assertThat(storedBytes).as("the object really landed in object storage").isPositive();
    assertThat(durationMillis)
        .as("the pass must fit the close's window at a million positions")
        .isLessThan(WINDOW.toMillis());
    // Recorded where an operator reads it, not only in this test's own log line.
    assertThat(
            jdbc.sql("SELECT duration_millis FROM workflow.snapshot_run WHERE run_id = :runId")
                .param("runId", result.run().runId())
                .query(Long.class)
                .single())
        .isEqualTo(durationMillis);
  }

  /**
   * Returns the fixture to a state a later run can still snapshot.
   *
   * <p>The projection side goes by dropping the tenant's own partitions, which is how Task 6.4's
   * ten-million-row fixture and Task 7.2's scale test both clean up — {@code
   * projection.participant_units} carries a {@code BEFORE DELETE} trigger that raises for every
   * role. It matters that this happens: fixtures are reused across runs (AGENTS.md §4), and a
   * million rows left behind would be scanned again by every later pass.
   *
   * <p><b>The ledger has to be returned to zero, and that is the part that is easy to get
   * wrong.</b> TigerBeetle cannot be reset and this fund's rows in {@code reference.fund_identity}
   * and {@code reference.fund_nav} cannot be deleted — both are append-only by trigger. So the fund
   * stays registered forever, and the snapshot checks <i>every</i> registered fund. Leaving a
   * hundred thousand units in issue behind a fund whose allotment rows this method deletes would
   * mean every later snapshot in this database — this suite's and anyone else's — failing to tie,
   * permanently, on what a previous run left behind. Reversing the issuance leaves the fund at zero
   * units and zero positions, which ties.
   *
   * <p>{@code workflow.snapshot_fund_total} is deliberately not cleaned: {@code V28} makes it
   * append-only for every role, which is the point of it. The rows are a handful per run and tie at
   * zero from here on.
   */
  @AfterEach
  void removeSyntheticRows() {
    reverseTheIssuance();
    if (tenantId != null) {
      String slug = slugOf(tenantId);
      jdbc.sql("DROP TABLE IF EXISTS projection.participant_units_t_" + slug + " CASCADE").update();
      jdbc.sql("DROP TABLE IF EXISTS projection.transfer_t_" + slug + " CASCADE").update();
      jdbc.sql("DELETE FROM projection.partitioned_tenant WHERE tenant_id = :t")
          .param("t", tenantId)
          .update();
    }
    jdbc.sql("DELETE FROM workflow.allotment WHERE batch_id = :batchId")
        .param("batchId", batchId)
        .update();
    jdbc.sql("DELETE FROM workflow.contribution_batch WHERE batch_id = :batchId")
        .param("batchId", batchId)
        .update();
  }

  // ---- fixture ---------------------------------------------------------------------------

  /** The rows the foreign keys require, plus the price the whole book is valued at. */
  private long seedPlatformRows() {
    jdbc.sql(
            """
            INSERT INTO master.organization (organization_id, tax_id_scheme, tax_id, legal_name,
                                             status, created_at, updated_at)
            VALUES (:organizationId, 'RFC', :taxId, 'Snapshot Scale SA de CV', 'ACTIVE',
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
            VALUES (:batchId, :organizationId, :objectKey, :checksum, 'RECEIVED', now(), now())
            """)
        .param("batchId", batchId)
        .param("organizationId", organizationId.value())
        .param("objectKey", "snapshot-scale/" + batchId)
        .param("checksum", "0".repeat(64))
        .update();
    tenantId = new TenantRefDeriver(LEDGER_NAMESPACE).derive(organizationId).asUnsignedDecimal();
    jdbc.sql("SELECT projection.ensure_tenant_partitions(:tenantId)")
        .param("tenantId", tenantId)
        .query((rs, rowNum) -> 1)
        .optional();
    openTheGates();
    return jdbc.sql(
            """
            INSERT INTO reference.fund_nav
              (fund_id, valuation_date, nav_scaled, units_in_issue, source, published_at)
            VALUES (:fundId, :date, :navScaled, 0, 'SCALE_FIXTURE', now())
            RETURNING nav_version_id
            """)
        .param("fundId", fundId.value())
        .param("date", businessDate)
        .param("navScaled", NAV_SCALED)
        .query(Long.class)
        .single();
  }

  /**
   * Records a passing internal and custodian reconciliation for this date.
   *
   * <p>Both gates are checked before the pass opens a run, and both fail closed, so without these
   * rows the snapshot would be refused before it read anything — which is the correct behaviour and
   * is asserted in {@code SnapshotServiceTest}. The business date is randomised far in the past, so
   * these rows can only be read by this test.
   */
  private void openTheGates() {
    jdbc.sql(
            """
            INSERT INTO workflow.reconciliation_run
              (run_id, business_date, status, started_at, completed_at, duration_millis)
            VALUES (gen_random_uuid(), :date, 'PASSED', now(), now(), 1)
            """)
        .param("date", businessDate)
        .update();
    jdbc.sql(
            """
            INSERT INTO workflow.custodian_reconciliation_run
              (run_id, business_date, status, started_at, completed_at, duration_millis)
            VALUES (gen_random_uuid(), :date, 'PASSED', now(), now(), 1)
            """)
        .param("date", businessDate)
        .update();
  }

  /** The candidate set and the cost basis: one lot per participant, at the same price. */
  private long loadHoldings(int participants, long navVersionId) {
    long inserted = 0;
    for (int loaded = 0; loaded < participants; loaded += LOAD_CHUNK_ROWS) {
      inserted +=
          jdbc.sql(
                  """
                  INSERT INTO workflow.allotment
                    (organization_id, batch_id, participant_internal_ref, source, fund_id,
                     nav_version_id, units_scaled, vested, transfer_id, allotted_at)
                  SELECT :organizationId, :batchId, 'SNAP-' || lpad(g::text, 9, '0'), 'EMPLOYEE',
                         :fundId, :navVersionId, :units, true, gen_random_uuid(), now()
                    FROM generate_series(:fromRow, :toRow) AS g
                  """)
              .param("organizationId", organizationId.value())
              .param("batchId", batchId)
              .param("fundId", fundId.value())
              .param("navVersionId", navVersionId)
              .param("units", UNITS_EACH)
              .param("fromRow", loaded)
              .param("toRow", Math.min(loaded + LOAD_CHUNK_ROWS, participants) - 1)
              .update();
    }
    analyze("workflow.allotment");
    return inserted;
  }

  /**
   * The projection's own view: one row per participant's <b>vested</b> holding account.
   *
   * <p>The ids are derived in Java, not generated in SQL, and they have to be: an account id is a
   * UUIDv5 over the derivation namespace and the participant's scope, which PostgreSQL cannot
   * compute. That is also the whole point — the pass looks these accounts up by re-deriving them,
   * so a fixture that invented ids would prove nothing about the derivation the close depends on.
   *
   * <p>Loaded with {@code COPY} rather than batched inserts. A million round trips through {@code
   * INSERT} is minutes of fixture to measure a read, and this test is about the read.
   */
  private long loadProjectedHoldings(int participants) {
    StringBuilder rows = new StringBuilder(LOAD_CHUNK_ROWS * 64);
    long copied = 0;
    try (Connection connection = dataSource.getConnection()) {
      var copyManager = connection.unwrap(PGConnection.class).getCopyAPI();
      for (int loaded = 0; loaded < participants; loaded += LOAD_CHUNK_ROWS) {
        rows.setLength(0);
        int to = Math.min(loaded + LOAD_CHUNK_ROWS, participants);
        for (int g = loaded; g < to; g++) {
          AccountId vested =
              accountIds.derive(
                  LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, holdingOf(g).scope());
          rows.append(tenantId)
              .append('\t')
              .append(vested.asUnsignedDecimal())
              .append('\t')
              .append(unitLedgerId)
              .append('\t')
              .append(businessDate)
              .append('\t')
              .append(UNITS_EACH)
              .append('\t')
              .append(vested.asUnsignedDecimal())
              .append('\t')
              .append("1800000000000000000")
              .append('\t')
              .append("\\N")
              .append('\t')
              .append("\\\\x00")
              .append('\n');
        }
        copied +=
            copyManager.copyIn(
                """
                COPY projection.participant_units
                  (tenant_id, participant_id, fund_id, effective_date, units_scaled,
                   last_transfer_id, last_ledger_timestamp, prev_row_hash, row_hash)
                FROM STDIN
                """,
                new StringReader(rows.toString()));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not load the projection fixture", e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    analyze("projection.participant_units_t_" + slugOf(tenantId));
    return copied;
  }

  /**
   * Puts the units on a real ledger, so the tie is against TigerBeetle rather than a double.
   *
   * <p>One transfer, not a million: the tie reads {@code UNITS_IN_ISSUE}'s balance and nothing
   * else, and posting a million real allotments would be minutes of writes to measure a read. The
   * contra-account is a holding account under a scope no participant in this fixture uses, so it
   * cannot be mistaken for one of them.
   *
   * <p>This test may write to the ledger; the service cannot. {@code LedgerConfiguration} wires no
   * {@code TransferIdDeriver}, and {@code SnapshotService} therefore has no way to express a
   * transfer at all — which is the guarantee, and it is not weakened by a fixture that has one.
   */
  private void issueUnitsOnTheLedger(long unitsInIssue) {
    ledger.createAccount(
        new CreateAccountCommand(
            unitsInIssueAccount(), unitLedgerId, LedgerCodes.AccountCode.UNITS_IN_ISSUE));
    ledger.createAccount(
        new CreateAccountCommand(
            sinkAccount(), unitLedgerId, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED));
    ledger.createTransfer(
        CreateTransferCommand.Unit.single(
            new TransferIdDeriver(LEDGER_NAMESPACE).derive("snapshot-scale", batchId.toString()),
            unitsInIssueAccount(),
            sinkAccount(),
            unitLedgerId,
            LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT,
            Units.of(unitsInIssue)));
  }

  /** Puts the fund back to zero units in issue — see {@link #removeSyntheticRows()} for why. */
  private void reverseTheIssuance() {
    ledger
        .lookupAccount(unitsInIssueAccount())
        .filter(account -> account instanceof LedgerAccount.Unit)
        .map(account -> ((LedgerAccount.Unit) account).balance())
        .filter(balance -> !balance.isZero())
        .ifPresent(
            balance ->
                ledger.createTransfer(
                    CreateTransferCommand.Unit.single(
                        new TransferIdDeriver(LEDGER_NAMESPACE)
                            .derive("snapshot-scale-reversal", batchId.toString()),
                        sinkAccount(),
                        unitsInIssueAccount(),
                        unitLedgerId,
                        LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT,
                        balance)));
  }

  private AccountId unitsInIssueAccount() {
    return accountIds.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fundId.value().toString());
  }

  /** A holding account under a scope no participant in this fixture uses. */
  private AccountId sinkAccount() {
    return accountIds.derive(
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, "scale-sink:" + batchId);
  }

  private HoldingKey holdingOf(int index) {
    return new HoldingKey(organizationId, String.format("SNAP-%09d", index), fundId);
  }

  /** Planner statistics for a table that just grew by a million rows. */
  private void analyze(String table) {
    jdbc.sql("ANALYZE " + table).update();
  }

  private static String slugOf(String tenantId) {
    return org.springframework.util.DigestUtils.md5DigestAsHex(
            tenantId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .substring(0, 16);
  }

  /**
   * A business date no other run has used.
   *
   * <p>Somewhere between the years 2300 and 9999. {@code workflow.snapshot_run} is never cleaned —
   * a completed snapshot is a record, and this suite has no business deleting one — so a date this
   * run picks that an earlier run also picked would make {@code latestCompletedFor} answer with a
   * stranger's snapshot, and this suite would halt on a mismatch against a file it never built. A
   * ten-thousand-day window collides about one run in a hundred once a few dozen have accumulated;
   * this one is 2.8 million days wide.
   *
   * <p>The upper bound is 9999 rather than PostgreSQL's own {@code date} ceiling for a mundane
   * reason worth recording: {@code LocalDate.toString()} renders a five-digit year as {@code
   * +867907-04-01}, and {@code COPY}'s text format rejects the leading sign.
   */
  private static LocalDate anUnusedBusinessDate() {
    return LocalDate.of(2300, 1, 1).plusDays(ThreadLocalRandom.current().nextLong(0L, 2_800_000L));
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

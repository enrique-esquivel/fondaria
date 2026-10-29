package dev.fondaria.projection.consume;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerEntryEvent;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Task 5.5 — the sparse per-participant unit holding ({@code projection.participant_units}).
 *
 * <p>Called from {@link JdbcProjectionSink#accept(LedgerEntryEvent)} and runs <b>inside that
 * method's transaction</b> — it opens none of its own (phase-05 Task 5.5) and touches no cache. The
 * holding row this writes and the checkpoint advance in the same method commit together.
 *
 * <h2>What a row is</h2>
 *
 * <p>One row per {@code (tenant_id, participant_id, fund_id, effective_date)}, written only on a
 * unit-ledger entry that moved a participant holding — <b>sparse</b>, never a row for a quiet day.
 * Value on a quiet day is {@code units(as-of) × NAV(day)}, derived and never stored
 * (docs/DOMAIN.md); "units as-of D" is the latest row with {@code effective_date <= D}.
 *
 * <ul>
 *   <li><b>{@code participant_id}</b> is the holding account's own TigerBeetle id (u128). It is the
 *       only participant-identifying value the CDC stream carries — the account was derived from
 *       the participant's scope by {@code allotment-job}, but that scope is not on the wire. {@code
 *       PARTICIPANT_HOLDINGS_VESTED} and {@code _UNVESTED} for one person and fund are different
 *       accounts, so they get different {@code participant_id}s and their own sparse series; a
 *       statement sums them. Resolving the account id back to a {@code master.participant} row is
 *       the Query API's join (Phase 6), not the projection's.
 *   <li><b>{@code fund_id}</b> is the fund's unit ledger id ({@code >= 1001}) — again the only fund
 *       key on the wire, and exactly what {@code projection.transfer.ledger_id} already carries.
 *   <li><b>{@code units_scaled}</b> is the account's post-event balance, {@code creditsPosted −
 *       debitsPosted}. A holding account carries {@code debits_must_not_exceed_credits}, so it is
 *       never negative; the column {@code CHECK} is the backstop and a negative value is failed
 *       loudly here rather than clamped (docs/ENGINEERING-RULES.md §4).
 *   <li><b>{@code effective_date}</b> is the entry's <i>value date</i> (O-4) — for v1 the business
 *       date of the ledger timestamp, never {@code LocalDate.now()}. The same rule Task 5.4 applies
 *       to a transfer's {@code value_date}.
 * </ul>
 *
 * <h2>Idempotent and order-free — including across a message's arrival date, not only its timestamp
 * </h2>
 *
 * <p>The upsert's {@code ON CONFLICT ... DO UPDATE} carries {@code WHERE
 * EXCLUDED.last_ledger_timestamp > stored}: a redelivery (same timestamp) and a late older event
 * (smaller timestamp) are both no-ops, and two entries on the same value date converge to the one
 * with the highest ledger timestamp. Delivering one entry a hundred times leaves one row. This part
 * was always arrival-order-independent and does not depend on {@code
 * rabbitmq_consistent_hash_exchange} routing an account's events to one single-consumer queue in
 * ledger order (Task F.3, {@code ADR-0024}).
 *
 * <h2>The hash chain, and why it needs a repair step that the value convergence above does not</h2>
 *
 * <p>{@code row_hash = SHA-256(prev_row_hash ‖ SEP ‖ participant_id ‖ SEP ‖ fund_id ‖ SEP ‖
 * effective_date ‖ SEP ‖ units_scaled ‖ SEP ‖ last_transfer_id)}, where {@code SEP} is the ASCII
 * unit separator {@code 0x1F}, {@code prev_row_hash} is the 32 raw bytes of the previous row's hash
 * for this {@code (tenant, participant, fund)} — the latest row with an earlier {@code
 * effective_date} — and every other field is its canonical text (US-ASCII; unsigned decimal, {@code
 * effective_date} as ISO-8601 {@code yyyy-MM-dd}). Chained per participant-fund so the holding
 * history is tamper-evident and recomputable years later from the rows alone (Task 5.7 recomputes
 * it).
 *
 * <p><b>A row's {@code prev_row_hash} is resolved against whatever has already been inserted at the
 * moment this row is written — which is a statement about arrival order, not about the true
 * (participant, fund, effective_date) sequence.</b> If the entry for an earlier {@code
 * effective_date} arrives <i>after</i> the entry for a later one — cross-date reordering, the exact
 * failure mode {@code ADR-0022} §2a and {@code BACKLOG.md} § "Known gaps" name — the later row's
 * {@code prev_row_hash} was set from whatever (possibly nothing) existed at that moment, and is now
 * stale once the earlier row lands. {@link #project} therefore repairs forward: after writing a
 * row, it finds the immediate next row by {@code effective_date} (if any) and, if that row's stored
 * {@code prev_row_hash} no longer matches what was just written, re-links it — recomputing its
 * {@code row_hash} from the corrected {@code prev_row_hash} — and repeats for its own successor,
 * and so on, until a link is found already consistent or the chain's current end is reached.
 * Changing one row's hash always changes what its successor's link must be, so this is not an
 * optimization that can stop early on any other condition. The cascade is bounded by how many
 * distinct dates this one holding has ever moved on, not by the size of any table, and runs inside
 * the same transaction as the write that triggered it — so a crash mid-cascade is exactly as safe
 * as a crash mid-insert (Task 5.7's replay recomputes from the durable rows either way).
 *
 * <p><b>This does not weaken the chain's tamper-evidence.</b> {@code row_hash} still commits to
 * {@code prev_row_hash}, so an unauthorized edit to any row still invalidates every hash after it —
 * exactly as before. What changed is only which insert order that computation is stable under: it
 * no longer requires the CDC pipeline to deliver in ledger-timestamp order, only that every event
 * eventually arrives (`docs/ENGINEERING-RULES.md` §4 — the platform never skips a message, so that
 * always holds).
 *
 * <h2>Concurrency — an advisory lock replaces the single-consumer-per-account guarantee</h2>
 *
 * <p>Before this task, the consistent-hash exchange's one-consumer-per-queue topology meant two
 * events for the same account were never processed concurrently, so the read-then-repair sequence
 * below never raced against itself. That guarantee is no longer assumed. {@link #project} opens
 * with {@code pg_advisory_xact_lock}, scoped to {@code (tenant, participant, fund)} and released
 * automatically at the enclosing transaction's commit or rollback, serializing this method for one
 * holding across however many consumer threads call it concurrently. It does not serialize
 * different holdings against each other.
 */
@Component
public class ParticipantUnitsProjection {

  private static final Logger LOGGER = LoggerFactory.getLogger(ParticipantUnitsProjection.class);

  private static final int VESTED = LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED.code();
  private static final int UNVESTED = LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED.code();

  /**
   * Field separator in the hash pre-image — ASCII unit separator, never present in a decimal id.
   */
  private static final byte HASH_FIELD_SEPARATOR = 0x1F;

  /**
   * Serializes {@link #project} for one {@code (tenant, participant, fund)} holding across
   * concurrent consumer threads — see the class javadoc's "Concurrency" section. A
   * transaction-scoped advisory lock needs a single key; {@code hashtext} folds the composite key
   * to one {@code int4}, which {@code pg_advisory_xact_lock(bigint)} widens without loss. A hash
   * collision between two different holdings only costs unnecessary serialization between them,
   * never incorrectness — this is a mutex, not an identity.
   */
  private static final String ADVISORY_LOCK = "SELECT pg_advisory_xact_lock(hashtext(:lockKey))";

  private static final String LATEST_EARLIER_ROW_HASH =
      """
      SELECT row_hash FROM projection.participant_units
       WHERE tenant_id = :tenantId
         AND participant_id = CAST(:participantId AS numeric)
         AND fund_id = :fundId
         AND effective_date < :effectiveDate
       ORDER BY effective_date DESC
       LIMIT 1
      """;

  private static final String UPSERT =
      """
      INSERT INTO projection.participant_units
        (tenant_id, participant_id, fund_id, effective_date, units_scaled,
         last_transfer_id, last_ledger_timestamp, prev_row_hash, row_hash)
      VALUES
        (:tenantId, CAST(:participantId AS numeric), :fundId, :effectiveDate, :unitsScaled,
         CAST(:lastTransferId AS numeric), CAST(:ledgerTimestamp AS numeric), :prevRowHash, :rowHash)
      ON CONFLICT (tenant_id, participant_id, fund_id, effective_date) DO UPDATE
      SET units_scaled          = EXCLUDED.units_scaled,
          last_transfer_id      = EXCLUDED.last_transfer_id,
          last_ledger_timestamp = EXCLUDED.last_ledger_timestamp,
          prev_row_hash         = EXCLUDED.prev_row_hash,
          row_hash              = EXCLUDED.row_hash
      WHERE EXCLUDED.last_ledger_timestamp > projection.participant_units.last_ledger_timestamp
      """;

  /**
   * The chain-repair cascade's read: the immediate next row by date, and what it needs to relink.
   */
  private static final String IMMEDIATE_SUCCESSOR =
      """
      SELECT effective_date, units_scaled, last_transfer_id, prev_row_hash
        FROM projection.participant_units
       WHERE tenant_id = :tenantId
         AND participant_id = CAST(:participantId AS numeric)
         AND fund_id = :fundId
         AND effective_date > :effectiveDate
       ORDER BY effective_date ASC
       LIMIT 1
      """;

  /**
   * The chain-repair cascade's write: re-links one row's {@code prev_row_hash} and the {@code
   * row_hash} recomputed from it. Never touches {@code units_scaled}, {@code last_transfer_id} or
   * {@code last_ledger_timestamp} — this is a hash-chain repair, not a business-data change, so it
   * carries no monotonicity guard of its own; the advisory lock is what makes it safe to run
   * unconditionally.
   */
  private static final String RELINK =
      """
      UPDATE projection.participant_units
         SET prev_row_hash = :newPrevRowHash,
             row_hash      = :newRowHash
       WHERE tenant_id = :tenantId
         AND participant_id = CAST(:participantId AS numeric)
         AND fund_id = :fundId
         AND effective_date = :effectiveDate
      """;

  private final JdbcClient jdbc;
  private final BusinessCalendar calendar;

  public ParticipantUnitsProjection(JdbcClient jdbc, BusinessCalendar calendar) {
    this.jdbc = jdbc;
    this.calendar = calendar;
  }

  /**
   * Project one entry event onto the sparse holding, when it is a participant unit-holding leg.
   * Anything else — a cash leg, {@code UNITS_IN_ISSUE}, {@code UNIT_ROUNDING} — is ignored here,
   * and the caller still advances the checkpoint for it. Since ADR-0040 the fan-out no longer
   * publishes those legs; the guard stays, because the entry contract allows them.
   */
  public void project(LedgerEntryEvent event) {
    if (!(event instanceof LedgerEntryEvent.Unit unit)) {
      return;
    }
    if (unit.accountCode() != VESTED && unit.accountCode() != UNVESTED) {
      return;
    }

    long unitsScaled = unit.creditsPosted().scaled() - unit.debitsPosted().scaled();
    if (unitsScaled < 0) {
      // A holding account carries debits_must_not_exceed_credits; a negative posted balance means
      // that ledger invariant was violated upstream. Throw (a transient failure -> retry) rather
      // than clamp a unit balance to zero (docs/ENGINEERING-RULES.md §4).
      throw new IllegalStateException(
          "unit holding account "
              + unit.accountId()
              + " reports a negative posted balance ("
              + unitsScaled
              + "); refusing to project it");
    }

    String tenantId = unit.accountUserData64();
    BigInteger participantId = new BigInteger(unit.accountId());
    int fundId = Math.toIntExact(unit.ledger());
    LocalDate effectiveDate = calendar.businessDateOf(LedgerTimestamps.toInstant(unit.timestamp()));
    BigInteger ledgerTimestamp = new BigInteger(unit.timestamp());
    BigInteger lastTransferId = new BigInteger(unit.transferId());

    // See the class javadoc's "Concurrency" section: this used to be guaranteed by the broker
    // topology (one consumer per account). It no longer is, so it is guaranteed here instead.
    lockHolding(tenantId, participantId, fundId);

    byte[] prevRowHash =
        jdbc.sql(LATEST_EARLIER_ROW_HASH)
            .param("tenantId", tenantId)
            .param("participantId", participantId.toString())
            .param("fundId", fundId)
            .param("effectiveDate", effectiveDate)
            .query(byte[].class)
            .optional()
            .orElse(null);

    byte[] rowHash =
        chainHash(prevRowHash, participantId, fundId, effectiveDate, unitsScaled, lastTransferId);

    int written =
        jdbc.sql(UPSERT)
            .param("tenantId", tenantId)
            .param("participantId", participantId.toString())
            .param("fundId", fundId)
            .param("effectiveDate", effectiveDate)
            .param("unitsScaled", unitsScaled)
            .param("lastTransferId", lastTransferId.toString())
            .param("ledgerTimestamp", ledgerTimestamp.toString())
            .param("prevRowHash", prevRowHash, Types.BINARY)
            .param("rowHash", rowHash, Types.BINARY)
            .update();

    if (written == 0) {
      LOGGER.debug(
          "holding {} in fund {} at {} unchanged by entry {} (redelivery or older event); no-op",
          participantId,
          fundId,
          effectiveDate,
          lastTransferId);
      return;
    }

    repairSuccessorChain(tenantId, participantId, fundId, effectiveDate, rowHash);
  }

  private void lockHolding(String tenantId, BigInteger participantId, int fundId) {
    String lockKey = tenantId + '|' + participantId + '|' + fundId;
    jdbc.sql(ADVISORY_LOCK).param("lockKey", lockKey).query().singleRow();
  }

  /**
   * Walks forward from the row just written, re-linking every successor whose {@code prev_row_hash}
   * no longer matches — see the class javadoc. Stops as soon as a successor is already correctly
   * linked: everything after it was necessarily chained against that successor's unchanged hash, so
   * the rest of the chain is still consistent.
   */
  private void repairSuccessorChain(
      String tenantId,
      BigInteger participantId,
      int fundId,
      LocalDate fromEffectiveDate,
      byte[] fromRowHash) {
    LocalDate cursorDate = fromEffectiveDate;
    byte[] cursorHash = fromRowHash;

    while (true) {
      List<Map<String, Object>> successors =
          jdbc.sql(IMMEDIATE_SUCCESSOR)
              .param("tenantId", tenantId)
              .param("participantId", participantId.toString())
              .param("fundId", fundId)
              .param("effectiveDate", cursorDate)
              .query()
              .listOfRows();
      if (successors.isEmpty()) {
        return;
      }
      Map<String, Object> successor = successors.get(0);

      byte[] storedPrev = (byte[]) successor.get("prev_row_hash");
      if (Arrays.equals(storedPrev, cursorHash)) {
        return; // already consistent — everything after this was chained against this hash too
      }

      LocalDate successorDate = ((java.sql.Date) successor.get("effective_date")).toLocalDate();
      long successorUnits = ((Number) successor.get("units_scaled")).longValue();
      BigInteger successorLastTransferId =
          new BigInteger(successor.get("last_transfer_id").toString());

      byte[] newSuccessorHash =
          chainHash(
              cursorHash,
              participantId,
              fundId,
              successorDate,
              successorUnits,
              successorLastTransferId);

      jdbc.sql(RELINK)
          .param("tenantId", tenantId)
          .param("participantId", participantId.toString())
          .param("fundId", fundId)
          .param("effectiveDate", successorDate)
          .param("newPrevRowHash", cursorHash, Types.BINARY)
          .param("newRowHash", newSuccessorHash, Types.BINARY)
          .update();

      LOGGER.debug(
          "relinked holding {} in fund {} at {} — an earlier-dated entry arrived after it",
          participantId,
          fundId,
          successorDate);

      cursorDate = successorDate;
      cursorHash = newSuccessorHash;
    }
  }

  /** See the class javadoc for the exact pre-image. */
  private static byte[] chainHash(
      byte[] prevRowHash,
      BigInteger participantId,
      int fundId,
      LocalDate effectiveDate,
      long unitsScaled,
      BigInteger lastTransferId) {
    MessageDigest sha256 = sha256();
    if (prevRowHash != null) {
      sha256.update(prevRowHash);
    }
    updateField(sha256, participantId.toString());
    updateField(sha256, Integer.toString(fundId));
    updateField(sha256, effectiveDate.toString());
    updateField(sha256, Long.toString(unitsScaled));
    updateField(sha256, lastTransferId.toString());
    return sha256.digest();
  }

  private static void updateField(MessageDigest digest, String value) {
    digest.update(HASH_FIELD_SEPARATOR);
    digest.update(value.getBytes(StandardCharsets.US_ASCII));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every Java platform", e);
    }
  }
}

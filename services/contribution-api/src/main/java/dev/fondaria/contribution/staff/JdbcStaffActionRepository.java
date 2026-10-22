package dev.fondaria.contribution.staff;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcStaffActionRepository implements StaffActionRepository {

  private final JdbcClient jdbc;
  private final Clock clock;

  public JdbcStaffActionRepository(JdbcClient jdbc, Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  @Transactional
  public StaffAction create(StaffAction action, String initialEventPayload) {
    Objects.requireNonNull(action, "action");

    jdbc.sql(
            """
            INSERT INTO workflow.staff_action (
                action_id, action_type, description, control_totals,
                requested_by, requested_at, status, approved_by, approved_at,
                is_break_glass, break_glass_reason, break_glass_expires_at
            ) VALUES (
                :actionId, :actionType, :description, :controlTotals::jsonb,
                :requestedBy, :requestedAt, :status, :approvedBy, :approvedAt,
                :isBreakGlass, :breakGlassReason, :breakGlassExpiresAt
            )
            """)
        .param("actionId", action.actionId())
        .param("actionType", action.actionType().name())
        .param("description", action.description())
        .param("controlTotals", action.controlTotalsJson())
        .param("requestedBy", action.requestedBy())
        .param("requestedAt", Timestamp.from(action.requestedAt()))
        .param("status", action.status().name())
        .param("approvedBy", action.approvedBy().orElse(null))
        .param("approvedAt", action.approvedAt().map(Timestamp::from).orElse(null))
        .param("isBreakGlass", action.isBreakGlass())
        .param("breakGlassReason", action.breakGlassReason().orElse(null))
        .param(
            "breakGlassExpiresAt", action.breakGlassExpiresAt().map(Timestamp::from).orElse(null))
        .update();

    StaffActionAuditEventType eventType =
        action.isBreakGlass()
            ? StaffActionAuditEventType.BREAK_GLASS_TRIGGERED
            : StaffActionAuditEventType.REQUESTED;

    appendAuditEntry(
        action.actionId(),
        action.actionType(),
        eventType,
        action.requestedBy(),
        action.requestedAt(),
        initialEventPayload != null && !initialEventPayload.isBlank() ? initialEventPayload : "{}");

    return action;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<StaffAction> findById(UUID actionId) {
    Objects.requireNonNull(actionId, "actionId");
    return jdbc.sql(
            """
            SELECT action_id, action_type, description, control_totals,
                   requested_by, requested_at, status, approved_by, approved_at,
                   is_break_glass, break_glass_reason, break_glass_expires_at
            FROM workflow.staff_action
            WHERE action_id = :actionId
            """)
        .param("actionId", actionId)
        .query(this::mapStaffAction)
        .optional();
  }

  @Override
  @Transactional
  public StaffAction approve(
      UUID actionId, String approver, Instant approvedAt, String approveEventPayload) {
    Objects.requireNonNull(actionId, "actionId");
    Objects.requireNonNull(approver, "approver");
    Objects.requireNonNull(approvedAt, "approvedAt");

    // Enforces requested_by <> approved_by at the database level via staff_action_four_eyes
    // constraint
    int updated =
        jdbc.sql(
                """
                UPDATE workflow.staff_action
                SET status = 'APPROVED',
                    approved_by = :approvedBy,
                    approved_at = :approvedAt
                WHERE action_id = :actionId AND status = 'PENDING_APPROVAL'
                """)
            .param("actionId", actionId)
            .param("approvedBy", approver)
            .param("approvedAt", Timestamp.from(approvedAt))
            .update();

    if (updated == 0) {
      StaffAction existing =
          findById(actionId)
              .orElseThrow(
                  () -> new IllegalArgumentException("Staff action not found: " + actionId));
      throw new IllegalStateException(
          "Action " + actionId + " cannot be approved in status " + existing.status());
    }

    StaffAction approved = findById(actionId).orElseThrow();

    appendAuditEntry(
        actionId,
        approved.actionType(),
        StaffActionAuditEventType.APPROVED,
        approver,
        approvedAt,
        approveEventPayload != null && !approveEventPayload.isBlank() ? approveEventPayload : "{}");

    return approved;
  }

  @Override
  @Transactional
  public StaffAction reject(UUID actionId, String rejector, Instant rejectedAt, String reason) {
    Objects.requireNonNull(actionId, "actionId");
    Objects.requireNonNull(rejector, "rejector");
    Objects.requireNonNull(rejectedAt, "rejectedAt");

    int updated =
        jdbc.sql(
                """
                UPDATE workflow.staff_action
                SET status = 'REJECTED'
                WHERE action_id = :actionId AND status = 'PENDING_APPROVAL'
                """)
            .param("actionId", actionId)
            .update();

    if (updated == 0) {
      StaffAction existing =
          findById(actionId)
              .orElseThrow(
                  () -> new IllegalArgumentException("Staff action not found: " + actionId));
      throw new IllegalStateException(
          "Action " + actionId + " cannot be rejected in status " + existing.status());
    }

    StaffAction rejected = findById(actionId).orElseThrow();

    String payload = String.format("{\"reason\":\"%s\"}", reason != null ? reason : "");
    appendAuditEntry(
        actionId,
        rejected.actionType(),
        StaffActionAuditEventType.REJECTED,
        rejector,
        rejectedAt,
        payload);

    return rejected;
  }

  @Override
  @Transactional(readOnly = true)
  public List<StaffActionAuditEntry> getAuditTrail(UUID actionId) {
    Objects.requireNonNull(actionId, "actionId");
    return jdbc.sql(
            """
            SELECT audit_id, sequence, action_id, action_type, event_type,
                   actor, recorded_at, payload, prev_hash, entry_hash
            FROM workflow.staff_action_audit
            WHERE action_id = :actionId
            ORDER BY sequence ASC
            """)
        .param("actionId", actionId)
        .query(this::mapAuditEntry)
        .list();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean verifyAuditChain() {
    List<StaffActionAuditEntry> entries =
        jdbc.sql(
                """
                SELECT audit_id, sequence, action_id, action_type, event_type,
                       actor, recorded_at, payload, prev_hash, entry_hash
                FROM workflow.staff_action_audit
                ORDER BY sequence ASC
                """)
            .query(this::mapAuditEntry)
            .list();

    String expectedPrevHash = StaffActionAuditHash.GENESIS_HASH;
    long expectedSeq = 1;

    for (StaffActionAuditEntry entry : entries) {
      if (entry.sequence() != expectedSeq) {
        return false;
      }
      if (!entry.prevHash().equals(expectedPrevHash)) {
        return false;
      }
      String calculatedHash =
          StaffActionAuditHash.computeHash(
              entry.prevHash(),
              entry.sequence(),
              entry.actionId(),
              entry.actionType(),
              entry.eventType(),
              entry.actor(),
              entry.recordedAt(),
              entry.payloadJson());
      if (!calculatedHash.equalsIgnoreCase(entry.entryHash())) {
        return false;
      }
      expectedPrevHash = entry.entryHash();
      expectedSeq++;
    }
    return true;
  }

  @Override
  @Transactional(readOnly = true)
  public int countActiveBreakGlassActions(Instant now) {
    Objects.requireNonNull(now, "now");
    Integer count =
        jdbc.sql(
                """
                SELECT COUNT(*)
                FROM workflow.staff_action
                WHERE is_break_glass = true
                  AND break_glass_expires_at > :now
                """)
            .param("now", Timestamp.from(now))
            .query(Integer.class)
            .single();
    return count != null ? count : 0;
  }

  private static final long AUDIT_APPEND_ADVISORY_LOCK_ID = 0x5354414646415544L; // "STAFFAUD"

  private void appendAuditEntry(
      UUID actionId,
      StaffActionType actionType,
      StaffActionAuditEventType eventType,
      String actor,
      Instant recordedAt,
      String payloadJson) {

    // Acquire transaction-scoped advisory lock to serialize audit log appending across concurrent
    // txns
    jdbc.sql("SELECT pg_advisory_xact_lock(:lockId)")
        .param("lockId", AUDIT_APPEND_ADVISORY_LOCK_ID)
        .query()
        .singleRow();

    // Read latest sequence and entry_hash
    Optional<AuditHead> headOpt =
        jdbc.sql(
                """
                SELECT sequence, entry_hash
                FROM workflow.staff_action_audit
                ORDER BY sequence DESC
                LIMIT 1
                """)
            .query(
                (rs, rowNum) -> new AuditHead(rs.getLong("sequence"), rs.getString("entry_hash")))
            .optional();

    long nextSeq = headOpt.map(h -> h.sequence + 1).orElse(1L);
    String prevHash = headOpt.map(h -> h.entryHash).orElse(StaffActionAuditHash.GENESIS_HASH);

    String entryHash =
        StaffActionAuditHash.computeHash(
            prevHash, nextSeq, actionId, actionType, eventType, actor, recordedAt, payloadJson);

    jdbc.sql(
            """
            INSERT INTO workflow.staff_action_audit (
                sequence, action_id, action_type, event_type, actor,
                recorded_at, payload, prev_hash, entry_hash
            ) VALUES (
                :sequence, :actionId, :actionType, :eventType, :actor,
                :recordedAt, :payload, :prevHash, :entryHash
            )
            """)
        .param("sequence", nextSeq)
        .param("actionId", actionId)
        .param("actionType", actionType.name())
        .param("eventType", eventType.name())
        .param("actor", actor)
        .param("recordedAt", Timestamp.from(recordedAt))
        .param("payload", payloadJson)
        .param("prevHash", prevHash)
        .param("entryHash", entryHash)
        .update();
  }

  private StaffAction mapStaffAction(ResultSet rs, int rowNum) throws SQLException {
    UUID actionId = rs.getObject("action_id", UUID.class);
    StaffActionType actionType = StaffActionType.valueOf(rs.getString("action_type"));
    String description = rs.getString("description");
    String controlTotals = rs.getString("control_totals");
    String requestedBy = rs.getString("requested_by");
    Instant requestedAt = rs.getTimestamp("requested_at").toInstant();
    StaffActionStatus status = StaffActionStatus.valueOf(rs.getString("status"));
    String approvedBy = rs.getString("approved_by");
    Timestamp approvedAtTs = rs.getTimestamp("approved_at");
    Instant approvedAt = approvedAtTs != null ? approvedAtTs.toInstant() : null;
    boolean isBreakGlass = rs.getBoolean("is_break_glass");
    String breakGlassReason = rs.getString("break_glass_reason");
    Timestamp breakGlassExpiresAtTs = rs.getTimestamp("break_glass_expires_at");
    Instant breakGlassExpiresAt =
        breakGlassExpiresAtTs != null ? breakGlassExpiresAtTs.toInstant() : null;

    return new StaffAction(
        actionId,
        actionType,
        description,
        controlTotals,
        requestedBy,
        requestedAt,
        status,
        Optional.ofNullable(approvedBy),
        Optional.ofNullable(approvedAt),
        isBreakGlass,
        Optional.ofNullable(breakGlassReason),
        Optional.ofNullable(breakGlassExpiresAt));
  }

  private StaffActionAuditEntry mapAuditEntry(ResultSet rs, int rowNum) throws SQLException {
    long auditId = rs.getLong("audit_id");
    long sequence = rs.getLong("sequence");
    UUID actionId = rs.getObject("action_id", UUID.class);
    StaffActionType actionType = StaffActionType.valueOf(rs.getString("action_type"));
    StaffActionAuditEventType eventType =
        StaffActionAuditEventType.valueOf(rs.getString("event_type"));
    String actor = rs.getString("actor");
    Instant recordedAt = rs.getTimestamp("recorded_at").toInstant();
    String payload = rs.getString("payload");
    String prevHash = rs.getString("prev_hash");
    String entryHash = rs.getString("entry_hash");

    return new StaffActionAuditEntry(
        auditId,
        sequence,
        actionId,
        actionType,
        eventType,
        actor,
        recordedAt,
        payload,
        prevHash,
        entryHash);
  }

  private record AuditHead(long sequence, String entryHash) {}
}

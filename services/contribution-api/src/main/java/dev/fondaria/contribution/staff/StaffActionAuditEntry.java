package dev.fondaria.contribution.staff;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only, hash-chained audit trail entry for privileged staff actions (Task 9.4). */
public record StaffActionAuditEntry(
    long auditId,
    long sequence,
    UUID actionId,
    StaffActionType actionType,
    StaffActionAuditEventType eventType,
    String actor,
    Instant recordedAt,
    String payloadJson,
    String prevHash,
    String entryHash) {

  public StaffActionAuditEntry {
    Objects.requireNonNull(actionId, "actionId");
    Objects.requireNonNull(actionType, "actionType");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(recordedAt, "recordedAt");
    Objects.requireNonNull(payloadJson, "payloadJson");
    Objects.requireNonNull(prevHash, "prevHash");
    Objects.requireNonNull(entryHash, "entryHash");
  }
}

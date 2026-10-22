package dev.fondaria.contribution.staff;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataIntegrityViolationException;

public class InMemoryStaffActionRepository implements StaffActionRepository {

  private final Map<UUID, StaffAction> actions = new ConcurrentHashMap<>();
  private final List<StaffActionAuditEntry> auditLog = new ArrayList<>();
  private long sequence = 0;
  private String lastHash = StaffActionAuditHash.GENESIS_HASH;

  @Override
  public synchronized StaffAction create(StaffAction action, String initialEventPayload) {
    actions.put(action.actionId(), action);
    sequence++;
    StaffActionAuditEventType eventType =
        action.isBreakGlass()
            ? StaffActionAuditEventType.BREAK_GLASS_TRIGGERED
            : StaffActionAuditEventType.REQUESTED;
    String entryHash =
        StaffActionAuditHash.computeHash(
            lastHash,
            sequence,
            action.actionId(),
            action.actionType(),
            eventType,
            action.requestedBy(),
            action.requestedAt(),
            initialEventPayload);
    StaffActionAuditEntry entry =
        new StaffActionAuditEntry(
            sequence,
            sequence,
            action.actionId(),
            action.actionType(),
            eventType,
            action.requestedBy(),
            action.requestedAt(),
            initialEventPayload,
            lastHash,
            entryHash);
    auditLog.add(entry);
    lastHash = entryHash;
    return action;
  }

  @Override
  public Optional<StaffAction> findById(UUID actionId) {
    return Optional.ofNullable(actions.get(actionId));
  }

  @Override
  public synchronized StaffAction approve(
      UUID actionId, String approver, Instant approvedAt, String approveEventPayload) {
    StaffAction existing = actions.get(actionId);
    if (existing == null) {
      throw new IllegalArgumentException("Staff action not found: " + actionId);
    }
    // Database constraint simulation: requested_by <> approved_by
    if (!existing.isBreakGlass() && approver.equalsIgnoreCase(existing.requestedBy())) {
      throw new DataIntegrityViolationException(
          "ERROR: new row for relation \"staff_action\" violates check constraint \"staff_action_four_eyes\"");
    }

    StaffAction approved =
        new StaffAction(
            existing.actionId(),
            existing.actionType(),
            existing.description(),
            existing.controlTotalsJson(),
            existing.requestedBy(),
            existing.requestedAt(),
            StaffActionStatus.APPROVED,
            Optional.of(approver),
            Optional.of(approvedAt),
            existing.isBreakGlass(),
            existing.breakGlassReason(),
            existing.breakGlassExpiresAt());
    actions.put(actionId, approved);

    sequence++;
    String entryHash =
        StaffActionAuditHash.computeHash(
            lastHash,
            sequence,
            actionId,
            existing.actionType(),
            StaffActionAuditEventType.APPROVED,
            approver,
            approvedAt,
            approveEventPayload);
    StaffActionAuditEntry entry =
        new StaffActionAuditEntry(
            sequence,
            sequence,
            actionId,
            existing.actionType(),
            StaffActionAuditEventType.APPROVED,
            approver,
            approvedAt,
            approveEventPayload,
            lastHash,
            entryHash);
    auditLog.add(entry);
    lastHash = entryHash;
    return approved;
  }

  @Override
  public synchronized StaffAction reject(
      UUID actionId, String rejector, Instant rejectedAt, String reason) {
    StaffAction existing = actions.get(actionId);
    if (existing == null) {
      throw new IllegalArgumentException("Staff action not found: " + actionId);
    }
    StaffAction rejected =
        new StaffAction(
            existing.actionId(),
            existing.actionType(),
            existing.description(),
            existing.controlTotalsJson(),
            existing.requestedBy(),
            existing.requestedAt(),
            StaffActionStatus.REJECTED,
            existing.approvedBy(),
            existing.approvedAt(),
            existing.isBreakGlass(),
            existing.breakGlassReason(),
            existing.breakGlassExpiresAt());
    actions.put(actionId, rejected);

    sequence++;
    String payload = String.format("{\"reason\":\"%s\"}", reason != null ? reason : "");
    String entryHash =
        StaffActionAuditHash.computeHash(
            lastHash,
            sequence,
            actionId,
            existing.actionType(),
            StaffActionAuditEventType.REJECTED,
            rejector,
            rejectedAt,
            payload);
    StaffActionAuditEntry entry =
        new StaffActionAuditEntry(
            sequence,
            sequence,
            actionId,
            existing.actionType(),
            StaffActionAuditEventType.REJECTED,
            rejector,
            rejectedAt,
            payload,
            lastHash,
            entryHash);
    auditLog.add(entry);
    lastHash = entryHash;
    return rejected;
  }

  @Override
  public synchronized List<StaffActionAuditEntry> getAuditTrail(UUID actionId) {
    return auditLog.stream().filter(e -> e.actionId().equals(actionId)).toList();
  }

  @Override
  public synchronized boolean verifyAuditChain() {
    String expectedPrevHash = StaffActionAuditHash.GENESIS_HASH;
    long expectedSeq = 1;
    for (StaffActionAuditEntry entry : auditLog) {
      if (entry.sequence() != expectedSeq) return false;
      if (!entry.prevHash().equals(expectedPrevHash)) return false;
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
      if (!calculatedHash.equalsIgnoreCase(entry.entryHash())) return false;
      expectedPrevHash = entry.entryHash();
      expectedSeq++;
    }
    return true;
  }

  @Override
  public synchronized int countActiveBreakGlassActions(Instant now) {
    return (int)
        actions.values().stream()
            .filter(
                a ->
                    a.isBreakGlass()
                        && a.breakGlassExpiresAt().map(exp -> exp.isAfter(now)).orElse(false))
            .count();
  }
}

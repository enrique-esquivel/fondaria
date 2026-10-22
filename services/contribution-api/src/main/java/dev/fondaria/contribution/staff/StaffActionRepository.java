package dev.fondaria.contribution.staff;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffActionRepository {

  StaffAction create(StaffAction action, String initialEventPayload);

  Optional<StaffAction> findById(UUID actionId);

  StaffAction approve(
      UUID actionId, String approver, Instant approvedAt, String approveEventPayload);

  StaffAction reject(UUID actionId, String rejector, Instant rejectedAt, String reason);

  List<StaffActionAuditEntry> getAuditTrail(UUID actionId);

  boolean verifyAuditChain();

  int countActiveBreakGlassActions(Instant now);
}

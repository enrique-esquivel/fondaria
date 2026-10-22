package dev.fondaria.contribution.staff;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Privileged staff action record (Task 9.4). */
public record StaffAction(
    UUID actionId,
    StaffActionType actionType,
    String description,
    String controlTotalsJson,
    String requestedBy,
    Instant requestedAt,
    StaffActionStatus status,
    Optional<String> approvedBy,
    Optional<Instant> approvedAt,
    boolean isBreakGlass,
    Optional<String> breakGlassReason,
    Optional<Instant> breakGlassExpiresAt) {

  public StaffAction {
    Objects.requireNonNull(actionId, "actionId");
    Objects.requireNonNull(actionType, "actionType");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(controlTotalsJson, "controlTotalsJson");
    Objects.requireNonNull(requestedBy, "requestedBy");
    Objects.requireNonNull(requestedAt, "requestedAt");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(approvedBy, "approvedBy");
    Objects.requireNonNull(approvedAt, "approvedAt");
    Objects.requireNonNull(breakGlassReason, "breakGlassReason");
    Objects.requireNonNull(breakGlassExpiresAt, "breakGlassExpiresAt");
  }

  public boolean isExpired(Instant now) {
    return isBreakGlass && breakGlassExpiresAt.map(exp -> now.isAfter(exp)).orElse(false);
  }
}

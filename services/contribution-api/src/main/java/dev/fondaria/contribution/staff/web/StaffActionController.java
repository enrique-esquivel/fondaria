package dev.fondaria.contribution.staff.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.common.security.AccessDeniedProblemException;
import dev.fondaria.common.security.CallerIdentity;
import dev.fondaria.common.security.IdentityContext;
import dev.fondaria.common.security.PopulationAccessControl;
import dev.fondaria.contribution.staff.BreakGlassMetrics;
import dev.fondaria.contribution.staff.StaffAction;
import dev.fondaria.contribution.staff.StaffActionRepository;
import dev.fondaria.contribution.staff.StaffActionStatus;
import dev.fondaria.contribution.staff.StaffActionType;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Privileged internal staff actions requiring four-eyes separation (Task 9.4).
 *
 * <p>Enforces:
 *
 * <ul>
 *   <li>The approver role cannot also request the action it approves.
 *   <li>The same person requesting and approving is rejected at the database level.
 *   <li>Every action records who, when, why, what changed, and stated control totals.
 *   <li>Break-glass access is possible, time-boxed, alerting, and reviewed after the fact.
 *   <li>Audit trail is append-only and cryptographically hash-chained.
 * </ul>
 */
@RestController
@RequestMapping("/v1/staff/actions")
public class StaffActionController {

  public record RequestActionPayload(
      String actionType,
      String description,
      Map<String, Object> controlTotals,
      boolean isBreakGlass,
      String breakGlassReason,
      Long breakGlassDurationSeconds) {

    public RequestActionPayload {
      if (actionType == null || actionType.isBlank()) {
        throw new IllegalArgumentException("actionType must not be blank");
      }
      if (description == null || description.isBlank()) {
        throw new IllegalArgumentException("description must not be blank");
      }
      if (isBreakGlass && (breakGlassReason == null || breakGlassReason.isBlank())) {
        throw new IllegalArgumentException("breakGlassReason is required for break-glass actions");
      }
    }
  }

  public record StaffActionResponse(
      UUID actionId,
      String actionType,
      String description,
      Map<String, Object> controlTotals,
      String requestedBy,
      Instant requestedAt,
      String status,
      String approvedBy,
      Instant approvedAt,
      boolean isBreakGlass,
      String breakGlassReason,
      Instant breakGlassExpiresAt) {}

  private final StaffActionRepository repository;
  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final BreakGlassMetrics breakGlassMetrics;

  public StaffActionController(
      StaffActionRepository repository,
      Clock clock,
      ObjectMapper objectMapper,
      BreakGlassMetrics breakGlassMetrics) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.breakGlassMetrics = Objects.requireNonNull(breakGlassMetrics, "breakGlassMetrics");
  }

  @PostMapping
  public ResponseEntity<StaffActionResponse> requestAction(
      @RequestBody RequestActionPayload payload) {
    CallerIdentity caller =
        IdentityContext.get()
            .orElseThrow(() -> new AccessDeniedProblemException("Authentication required"));

    PopulationAccessControl.assertCanRequestFourEyesAction(caller);

    StaffActionType actionType = StaffActionType.fromString(payload.actionType());
    UUID id = UUID.randomUUID();
    Instant now = clock.instant();

    String controlTotalsJson;
    try {
      controlTotalsJson =
          objectMapper.writeValueAsString(
              payload.controlTotals() != null ? payload.controlTotals() : Map.of());
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid controlTotals format", e);
    }

    boolean isBreakGlass = payload.isBreakGlass();
    Optional<String> breakGlassReason =
        isBreakGlass ? Optional.of(payload.breakGlassReason()) : Optional.empty();
    Optional<Instant> breakGlassExpiresAt = Optional.empty();

    if (isBreakGlass) {
      long durationSec =
          payload.breakGlassDurationSeconds() != null
              ? Math.min(3600L, Math.max(60L, payload.breakGlassDurationSeconds()))
              : 900L; // Default 15m, max 1h
      breakGlassExpiresAt = Optional.of(now.plusSeconds(durationSec));
    }

    StaffActionStatus initialStatus =
        isBreakGlass ? StaffActionStatus.APPROVED : StaffActionStatus.PENDING_APPROVAL;
    Optional<String> approvedBy = isBreakGlass ? Optional.of(caller.subject()) : Optional.empty();
    Optional<Instant> approvedAt = isBreakGlass ? Optional.of(now) : Optional.empty();

    StaffAction action =
        new StaffAction(
            id,
            actionType,
            payload.description(),
            controlTotalsJson,
            caller.subject(),
            now,
            initialStatus,
            approvedBy,
            approvedAt,
            isBreakGlass,
            breakGlassReason,
            breakGlassExpiresAt);

    String initialEventPayload;
    try {
      initialEventPayload =
          objectMapper.writeValueAsString(
              Map.of(
                  "description",
                  payload.description(),
                  "controlTotals",
                  payload.controlTotals() != null ? payload.controlTotals() : Map.of(),
                  "isBreakGlass",
                  isBreakGlass,
                  "breakGlassReason",
                  breakGlassReason.orElse("")));
    } catch (JsonProcessingException e) {
      initialEventPayload = "{}";
    }

    StaffAction created = repository.create(action, initialEventPayload);

    if (isBreakGlass) {
      breakGlassMetrics.recordBreakGlassTriggered();
    }

    return ResponseEntity.created(URI.create("/v1/staff/actions/" + id)).body(toResponse(created));
  }

  @PostMapping("/{actionId}/approve")
  public ResponseEntity<StaffActionResponse> approveAction(
      @PathVariable("actionId") String actionIdRaw) {
    CallerIdentity caller =
        IdentityContext.get()
            .orElseThrow(() -> new AccessDeniedProblemException("Authentication required"));

    UUID actionId;
    try {
      actionId = UUID.fromString(actionIdRaw);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("actionId is not a valid UUID");
    }

    StaffAction existing =
        repository
            .findById(actionId)
            .orElseThrow(() -> new IllegalArgumentException("Staff action not found"));

    // Code-level check
    PopulationAccessControl.assertCanApproveFourEyesAction(caller, existing.requestedBy());

    Instant now = clock.instant();
    String approveEventPayload =
        String.format("{\"approvedBy\":\"%s\",\"approvedAt\":\"%s\"}", caller.subject(), now);

    // Enforced in repository via PostgreSQL DB constraint staff_action_four_eyes
    StaffAction approved = repository.approve(actionId, caller.subject(), now, approveEventPayload);

    return ResponseEntity.ok(toResponse(approved));
  }

  @GetMapping("/{actionId}")
  public ResponseEntity<StaffActionResponse> getAction(
      @PathVariable("actionId") String actionIdRaw) {
    CallerIdentity caller =
        IdentityContext.get()
            .orElseThrow(() -> new AccessDeniedProblemException("Authentication required"));
    if (!caller.isInternalStaff()) {
      throw new AccessDeniedProblemException("Forbidden: Internal staff required");
    }

    UUID actionId = UUID.fromString(actionIdRaw);
    return repository
        .findById(actionId)
        .map(a -> ResponseEntity.ok(toResponse(a)))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
  }

  private StaffActionResponse toResponse(StaffAction action) {
    Map<String, Object> controlTotalsMap;
    try {
      controlTotalsMap = objectMapper.readValue(action.controlTotalsJson(), Map.class);
    } catch (Exception e) {
      controlTotalsMap = Map.of();
    }

    return new StaffActionResponse(
        action.actionId(),
        action.actionType().name(),
        action.description(),
        controlTotalsMap,
        action.requestedBy(),
        action.requestedAt(),
        action.status().name(),
        action.approvedBy().orElse(null),
        action.approvedAt().orElse(null),
        action.isBreakGlass(),
        action.breakGlassReason().orElse(null),
        action.breakGlassExpiresAt().orElse(null));
  }
}

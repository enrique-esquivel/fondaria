package dev.fondaria.contribution.participant.web;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.participant.ParticipantLookup;
import dev.fondaria.contribution.participant.ParticipantLookupRepository;
import dev.fondaria.contribution.participant.ParticipantNotFoundException;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resolves a participant's {@code participantId} — the second thing Task D.0 exists to add. Until
 * now enrolment (Task 2b.3) assigned it, but nothing returned it; the e2e tests read it back with
 * SQL ({@code Scenario.resolveParticipant}), and there was no way to look it up on a cluster.
 *
 * <p>Exactly one of {@code internalRef} or {@code payrollRef} must be supplied. A paged listing
 * without a filter is out of scope for this endpoint.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}/participants")
public class ParticipantLookupController {

  private final ParticipantLookupRepository lookups;

  public ParticipantLookupController(ParticipantLookupRepository lookups) {
    this.lookups = lookups;
  }

  @GetMapping
  public ParticipantLookupResponse lookup(
      @PathVariable("organizationId") String organizationIdRaw,
      @RequestParam(name = "internalRef", required = false) String internalRef,
      @RequestParam(name = "payrollRef", required = false) String payrollRef) {
    OrganizationId organizationId = parseOrganizationId(organizationIdRaw);
    boolean hasInternalRef = internalRef != null && !internalRef.isBlank();
    boolean hasPayrollRef = payrollRef != null && !payrollRef.isBlank();

    if (hasInternalRef == hasPayrollRef) {
      throw new InvalidParticipantLookupRequestException(
          "exactly one of internalRef or payrollRef must be supplied");
    }

    Optional<ParticipantLookup> found =
        hasInternalRef
            ? lookups.findByInternalRef(organizationId, internalRef.trim())
            : lookups.findByPayrollRef(organizationId, payrollRef.trim());

    return ParticipantLookupResponse.of(found.orElseThrow(ParticipantNotFoundException::new));
  }

  private static OrganizationId parseOrganizationId(String raw) {
    try {
      return OrganizationId.parse(raw);
    } catch (IllegalArgumentException e) {
      throw new InvalidParticipantLookupRequestException("organizationId is not a valid UUID");
    }
  }
}

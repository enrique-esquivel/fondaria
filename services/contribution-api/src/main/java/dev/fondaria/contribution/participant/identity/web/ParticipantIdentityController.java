package dev.fondaria.contribution.participant.identity.web;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import dev.fondaria.contribution.participant.ParticipantLookup;
import dev.fondaria.contribution.participant.ParticipantLookupRepository;
import dev.fondaria.contribution.participant.ParticipantNotFoundException;
import dev.fondaria.contribution.participant.identity.ParticipantIdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task 9.5: capture and crypto-shred a participant's identity fields.
 *
 * <p>Authorization is enforced entirely by {@code AuthorisationEnforcer} (Task 9.3's single
 * enforcement point), before this controller ever runs — an employer admin scoped to {@code
 * organizationId} for {@link #capture}, internal staff or a service identity only for {@link
 * #destroy}. Nothing here re-checks a role.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}/participants/{participantId}/identity")
public class ParticipantIdentityController {

  private final ParticipantLookupRepository lookups;
  private final ParticipantIdentityService service;

  public ParticipantIdentityController(
      ParticipantLookupRepository lookups, ParticipantIdentityService service) {
    this.lookups = lookups;
    this.service = service;
  }

  @PutMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void capture(
      @PathVariable("organizationId") String organizationIdRaw,
      @PathVariable("participantId") String participantIdRaw,
      @RequestBody IdentityCaptureRequest request) {
    OrganizationId organizationId = parseOrganizationId(organizationIdRaw);
    String internalRef = resolveInternalRef(organizationId, parseParticipantId(participantIdRaw));
    service.captureIdentity(organizationId, internalRef, request.toCommand());
  }

  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void destroy(
      @PathVariable("organizationId") String organizationIdRaw,
      @PathVariable("participantId") String participantIdRaw) {
    OrganizationId organizationId = parseOrganizationId(organizationIdRaw);
    String internalRef = resolveInternalRef(organizationId, parseParticipantId(participantIdRaw));
    service.destroyKey(organizationId, internalRef);
  }

  private String resolveInternalRef(OrganizationId organizationId, ParticipantId participantId) {
    return lookups
        .findByParticipantId(organizationId, participantId)
        .map(ParticipantLookup::internalRef)
        .orElseThrow(ParticipantNotFoundException::new);
  }

  private static OrganizationId parseOrganizationId(String raw) {
    try {
      return OrganizationId.parse(raw);
    } catch (IllegalArgumentException e) {
      throw new InvalidIdentityPathException("organizationId is not a valid UUID");
    }
  }

  private static ParticipantId parseParticipantId(String raw) {
    try {
      return ParticipantId.parse(raw);
    } catch (IllegalArgumentException e) {
      throw new InvalidIdentityPathException("participantId is not a valid UUID");
    }
  }
}

package dev.fondaria.contribution.organization.web;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.organization.Organization;
import dev.fondaria.contribution.organization.OrganizationRegistration;
import dev.fondaria.contribution.organization.OrganizationService;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organization registration and maintenance (Task 2b.1).
 *
 * <p>Every path is keyed by the derived {@code organizationId}. The tax identifier appears exactly
 * once — in the registration body — and never in a path, a query parameter, a response or a log
 * (AGENTS.md §7).
 *
 * <p>Every binding annotation names its parameter explicitly. The build does not compile with
 * {@code -parameters}, so an unnamed {@code @PathVariable} resolves to nothing at runtime and every
 * request it appears on fails — a failure that only shows up when the endpoint is called, never at
 * compile time.
 */
@RestController
@RequestMapping("/v1/organizations")
public class OrganizationController {

  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 200;

  private final OrganizationService organizations;

  public OrganizationController(OrganizationService organizations) {
    this.organizations = organizations;
  }

  /**
   * Registers an organization, or returns the one already registered under the same tax identifier.
   *
   * <p>{@code 201} the first time, {@code 200} afterwards — the same organization either way. There
   * is no {@code 409}: registering twice is not a conflict, it is the same statement made twice,
   * and rejecting the second would leave a client that lost its response with no way to converge.
   */
  @PostMapping
  public ResponseEntity<OrganizationResponse> register(
      @RequestBody RegisterOrganizationRequest request) {
    OrganizationRegistration registration = organizations.register(request.toCommand());
    OrganizationResponse body = OrganizationResponse.of(registration.organization());
    URI location = URI.create("/v1/organizations/" + body.organizationId());
    return (registration.created()
            ? ResponseEntity.created(location)
            : ResponseEntity.ok().location(location))
        .body(body);
  }

  @GetMapping("/{organizationId}")
  public OrganizationResponse get(@PathVariable("organizationId") String organizationId) {
    OrganizationId id = parseId(organizationId);
    return OrganizationResponse.of(organizations.get(id));
  }

  /** The lookup the phase exists to restore: organizations can be listed at all. */
  @GetMapping
  public List<OrganizationResponse> list(
      @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit,
      @RequestParam(name = "offset", defaultValue = "0") int offset) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new InvalidOrganizationRequestException("limit must be between 1 and " + MAX_LIMIT);
    }
    if (offset < 0) {
      throw new InvalidOrganizationRequestException("offset must not be negative");
    }
    return organizations.list(limit, offset).stream().map(OrganizationResponse::of).toList();
  }

  /** Never changes the id, and never changes a ledger account — see {@link OrganizationService}. */
  @PatchMapping("/{organizationId}")
  public OrganizationResponse update(
      @PathVariable("organizationId") String organizationId,
      @RequestBody UpdateOrganizationRequest request) {
    OrganizationId id = parseId(organizationId);
    Organization updated = organizations.update(id, request.toChange());
    return OrganizationResponse.of(updated);
  }

  /** The history an update appends to rather than overwrites. */
  @GetMapping("/{organizationId}/revisions")
  public List<OrganizationResponse.Revision> revisions(
      @PathVariable("organizationId") String organizationId) {
    OrganizationId id = parseId(organizationId);
    return organizations.revisions(id).stream().map(OrganizationResponse.Revision::of).toList();
  }

  private static OrganizationId parseId(String raw) {
    try {
      return OrganizationId.parse(raw);
    } catch (IllegalArgumentException e) {
      throw new InvalidOrganizationRequestException("organizationId is not a valid UUID");
    }
  }
}

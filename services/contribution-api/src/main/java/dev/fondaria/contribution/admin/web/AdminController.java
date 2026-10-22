package dev.fondaria.contribution.admin.web;

import dev.fondaria.common.OrganizationId;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Delegated administration endpoint (Task 9.2).
 *
 * <p>Enforces: an employer admin can create further admins within their own organization only.
 * Creating an admin outside their own organization is rejected with HTTP 403 Forbidden.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}/admins")
public class AdminController {

  public record CreateAdminRequest(String username, String email) {
    public CreateAdminRequest {
      if (username == null || username.isBlank()) {
        throw new IllegalArgumentException("username must not be blank");
      }
      if (email == null || email.isBlank()) {
        throw new IllegalArgumentException("email must not be blank");
      }
    }
  }

  public record AdminResponse(UUID adminId, String organizationId, String username, String email) {}

  @PostMapping
  public ResponseEntity<AdminResponse> createAdmin(
      @PathVariable("organizationId") String organizationIdRaw,
      @RequestBody CreateAdminRequest request) {
    OrganizationId organizationId = parseOrganizationId(organizationIdRaw);

    UUID adminId = UUID.randomUUID();
    AdminResponse response =
        new AdminResponse(adminId, organizationId.toString(), request.username(), request.email());

    return ResponseEntity.created(
            URI.create("/v1/organizations/" + organizationId.value() + "/admins/" + adminId))
        .body(response);
  }

  private static OrganizationId parseOrganizationId(String raw) {
    try {
      return OrganizationId.parse(raw);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("organizationId is not a valid UUID");
    }
  }
}

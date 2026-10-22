package dev.fondaria.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CallerIdentityParserTest {

  @Test
  @DisplayName("Parses participant claims correctly")
  void parsesParticipantClaims() {
    UUID participantUuid = UUID.randomUUID();
    String claimsJson =
        """
        {
          "sub": "%s",
          "realm_access": {
            "roles": ["participant", "default-roles-fondaria"]
          }
        }
        """
            .formatted(participantUuid);

    String jwt = createJwt(claimsJson);
    Optional<CallerIdentity> identityOpt = CallerIdentityParser.parseBearerHeader("Bearer " + jwt);

    assertThat(identityOpt).isPresent();
    CallerIdentity id = identityOpt.get();
    assertThat(id.subject()).isEqualTo(participantUuid.toString());
    assertThat(id.population()).isEqualTo(UserPopulation.PARTICIPANT);
    assertThat(id.participantId()).contains(ParticipantId.of(participantUuid));
    assertThat(id.organizationId()).isEmpty();
  }

  @Test
  @DisplayName("Parses employer admin claims with bound organization correctly")
  void parsesEmployerAdminClaims() {
    UUID orgUuid = UUID.randomUUID();
    String claimsJson =
        """
        {
          "sub": "user-employer-123",
          "organization_id": "%s",
          "realm_access": {
            "roles": ["employer-admin"]
          }
        }
        """
            .formatted(orgUuid);

    String jwt = createJwt(claimsJson);
    Optional<CallerIdentity> identityOpt = CallerIdentityParser.parseBearerHeader("Bearer " + jwt);

    assertThat(identityOpt).isPresent();
    CallerIdentity id = identityOpt.get();
    assertThat(id.subject()).isEqualTo("user-employer-123");
    assertThat(id.population()).isEqualTo(UserPopulation.EMPLOYER_ADMIN);
    assertThat(id.organizationId()).contains(new OrganizationId(orgUuid));
    assertThat(id.isEmployerAdmin()).isTrue();
  }

  @Test
  @DisplayName("Parses internal operations staff claims correctly")
  void parsesInternalOperationsStaffClaims() {
    String claimsJson =
        """
        {
          "sub": "staff-op-99",
          "realm_access": {
            "roles": ["internal-staff", "internal-operations"]
          }
        }
        """;

    String jwt = createJwt(claimsJson);
    Optional<CallerIdentity> identityOpt = CallerIdentityParser.parseBearerHeader("Bearer " + jwt);

    assertThat(identityOpt).isPresent();
    CallerIdentity id = identityOpt.get();
    assertThat(id.population()).isEqualTo(UserPopulation.INTERNAL_STAFF);
    assertThat(id.hasStaffRole(StaffRole.OPERATIONS)).isTrue();
    assertThat(id.hasStaffRole(StaffRole.APPROVER)).isFalse();
  }

  @Test
  @DisplayName("Returns empty on invalid header")
  void returnsEmptyOnInvalidHeader() {
    assertThat(CallerIdentityParser.parseBearerHeader(null)).isEmpty();
    assertThat(CallerIdentityParser.parseBearerHeader("")).isEmpty();
    assertThat(CallerIdentityParser.parseBearerHeader("Basic xyz")).isEmpty();
    assertThat(CallerIdentityParser.parseBearerHeader("Bearer ")).isEmpty();
  }

  private static String createJwt(String payloadJson) {
    String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
    String encodedHeader =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(header.getBytes(StandardCharsets.UTF_8));
    String encodedPayload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
    String dummySignature = "dummy-sig";
    return encodedHeader + "." + encodedPayload + "." + dummySignature;
  }
}

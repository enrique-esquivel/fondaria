package dev.fondaria.balanceapi.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link ParticipantScope} — and specifically, the string a participant's ledger holding accounts
 * are derived under.
 *
 * <p><b>This is the highest-consequence, lowest-visibility coupling in the service.</b> {@code
 * AllotmentService.participantScope} in {@code services/allotment-job} built that string when it
 * opened the accounts:
 *
 * <pre>batch.organizationId().scope() + ":" + participantInternalRef + ":" + fundId</pre>
 *
 * <p>and {@link ParticipantScope#forFund} must reproduce it byte for byte. Services do not depend
 * on each other's source trees (docs/ENGINEERING-RULES.md §4), so the compiler cannot check this
 * and no integration test spans both — the balance suite issues units under its own copy of the
 * derivation, which would drift in lockstep with this one and prove nothing.
 *
 * <p><b>A drift does not fail; it derives ids for accounts that do not exist</b>, {@code
 * lookupAccounts} returns nothing for them, and every participant is reported as holding zero — a
 * wrong balance wearing the shape of a right one. So the format is pinned here as a literal. If
 * this test fails, do not update the literal: check whether {@code
 * AllotmentService.participantScope} changed, and fix whichever side moved.
 */
class ParticipantScopeTest {

  private static final OrganizationId ORGANIZATION =
      new OrganizationId(UUID.fromString("2f1c8a5e-0000-4000-8000-000000000001"));
  private static final FundId FUND =
      FundId.of(UUID.fromString("7b3e91d4-0000-4000-8000-0000000000ff"));

  @Test
  void derivesTheScopeAllotmentServiceOpenedTheAccountsUnder() {
    ParticipantScope scope = new ParticipantScope(ORGANIZATION, "EMP-000123");

    assertThat(scope.forFund(FUND))
        .isEqualTo(
            "2f1c8a5e-0000-4000-8000-000000000001:EMP-000123:7b3e91d4-0000-4000-8000-0000000000ff");
  }

  /**
   * Organization first, because {@code participantInternalRef} is unique only within an
   * organization (docs/ENGINEERING-RULES.md §4, and {@code master.participant}'s own primary key).
   * Two employers may legitimately use the same internal ref, and a scope that led with it would
   * collapse their holdings into one account.
   */
  @Test
  void twoOrganizationsSharingAnInternalRefDeriveDifferentScopes() {
    OrganizationId other = new OrganizationId(UUID.randomUUID());

    assertThat(new ParticipantScope(ORGANIZATION, "EMP-1").forFund(FUND))
        .isNotEqualTo(new ParticipantScope(other, "EMP-1").forFund(FUND));
  }

  @Test
  void oneParticipantsTwoFundsAreDifferentScopes() {
    ParticipantScope scope = new ParticipantScope(ORGANIZATION, "EMP-1");

    assertThat(scope.forFund(FUND)).isNotEqualTo(scope.forFund(FundId.of(UUID.randomUUID())));
  }

  @Test
  void rejectsABlankInternalRef() {
    assertThatThrownBy(() -> new ParticipantScope(ORGANIZATION, "  "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

package dev.fondaria.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.contribution.support.ServiceIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The probe grouping is one line of {@code application.yaml} away from silently doing nothing, and
 * getting it wrong is expensive in both directions: omitted from readiness, an unreachable ledger
 * goes unnoticed; added to liveness, a ledger outage becomes a fleet-wide restart loop. Asserted
 * here rather than by driving HTTP, because the status-to-503 mapping is Boot's behaviour, not
 * ours.
 *
 * <p>Points at the real fixtures rather than a made-up address: since Task 2b.1 the context also
 * holds a {@code DataSource} and a {@code LedgerPort}, so booting it means genuinely reaching both.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HealthGroupsTest extends ServiceIntegrationTest {

  @Autowired private HealthEndpointGroups groups;

  @Test
  @DisplayName("the ledger probe is part of readiness")
  void ledgerIsInReadiness() {
    assertThat(groups.get("readiness").isMember("ledger")).isTrue();
  }

  @Test
  @DisplayName("the ledger probe is never part of liveness")
  void ledgerIsNotInLiveness() {
    assertThat(groups.get("liveness").isMember("ledger")).isFalse();
  }
}

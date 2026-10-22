package dev.fondaria.eodjob.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.ledger.LedgerLimits;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReconciliationPropertiesTest {

  @Test
  @DisplayName("the account-lookup ceiling is the ledger's own per-request limit, not a guess")
  void theCeilingTracksTheLedger() {
    assertThat(ReconciliationProperties.MAX_ACCOUNT_LOOKUP_BATCH)
        .as(
            "the literal in ReconciliationProperties exists so that record stays a plain binding"
                + " target; this assertion is what keeps it honest if LedgerLimits ever moves")
        .isEqualTo(LedgerLimits.MAX_LINKED_CHAIN_LENGTH);
  }

  @Test
  @DisplayName("a batch larger than one ledger request fails startup rather than being truncated")
  void anOversizedBatchFailsStartup() {
    assertThatThrownBy(
            () ->
                new ReconciliationProperties(
                    Duration.ofHours(1), LedgerLimits.MAX_LINKED_CHAIN_LENGTH + 1, 50_000))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("one ledger request can carry");
  }

  @Test
  @DisplayName("absent settings bind to working defaults: this is tuning, not a secret")
  void absentSettingsDefault() {
    ReconciliationProperties properties = new ReconciliationProperties(null, 0, 0);

    assertThat(properties.pollInterval()).isEqualTo(Duration.ofHours(1));
    assertThat(properties.accountLookupBatchSize()).isEqualTo(LedgerLimits.MAX_LINKED_CHAIN_LENGTH);
    assertThat(properties.holdingPageSize()).isEqualTo(50_000);
  }
}

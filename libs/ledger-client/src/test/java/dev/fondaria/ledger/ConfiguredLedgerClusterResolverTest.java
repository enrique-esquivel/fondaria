package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfiguredLedgerClusterResolverTest {

  private static final ClusterId PRIMARY = ClusterId.of("primary");
  private static final ClusterId SECONDARY = ClusterId.of("secondary");

  @Test
  void singleClusterResolvesEveryLedgerIdToTheSameCluster() {
    ConfiguredLedgerClusterResolver resolver =
        ConfiguredLedgerClusterResolver.singleCluster(PRIMARY);

    assertThat(resolver.resolveCluster(LedgerCodes.CASH_LEDGER_ID)).isEqualTo(PRIMARY);
    assertThat(resolver.resolveCluster(1001L)).isEqualTo(PRIMARY);
    assertThat(resolver.resolveCluster(9_999_999L)).isEqualTo(PRIMARY);
  }

  @Test
  void anOverrideWinsOverTheDefaultForItsOwnLedgerIdOnly() {
    ConfiguredLedgerClusterResolver resolver =
        new ConfiguredLedgerClusterResolver(PRIMARY, Map.of(1002L, SECONDARY));

    assertThat(resolver.resolveCluster(1001L)).isEqualTo(PRIMARY);
    assertThat(resolver.resolveCluster(1002L)).isEqualTo(SECONDARY);
    assertThat(resolver.resolveCluster(LedgerCodes.CASH_LEDGER_ID)).isEqualTo(PRIMARY);
  }

  @Test
  void rejectsANullDefaultCluster() {
    assertThatThrownBy(() -> new ConfiguredLedgerClusterResolver(null, Map.of()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void rejectsANullOverridesMap() {
    assertThatThrownBy(() -> new ConfiguredLedgerClusterResolver(PRIMARY, null))
        .isInstanceOf(NullPointerException.class);
  }
}

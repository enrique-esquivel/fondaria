package dev.fondaria.demo.close;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HashChainCheckTest {

  @Test
  void genesisManifestLinksToNothing() {
    HashChainCheck chain = HashChainCheck.fromRow(List.of("1", "0", "", "", ""));

    assertThat(chain.intact()).isTrue();
    assertThat(chain.describe()).contains("genesis manifest");
  }

  @Test
  void aBrokenLinkIsNamed() {
    HashChainCheck chain = HashChainCheck.fromRow(List.of("4", "1", "2026-10-22", "", ""));

    assertThat(chain.intact()).isFalse();
    assertThat(chain.describe()).startsWith("BROKEN").contains("first at 2026-10-22");
  }
}

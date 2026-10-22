package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * ADR-0030 follow-up to Task 10.4: Calico WireGuard encrypts node-to-node traffic, including the
 * unmeshed datastore tier's connections (ADR-0029), without any infrastructure/k8s/base manifest
 * change. Live-verified this session: Calico accepted {@code wireguardEnabled: true} cleanly on the
 * local kind cluster, created the {@code wireguard.cali} interface and generated a key pair, with
 * no errors in calico-node's logs. Not verified, and disclosed rather than claimed: an actual
 * encrypted tunnel between two nodes -- this cluster is single-node, so there is no second node for
 * one to terminate on. Same-node pod traffic is never covered by this control at all (a real,
 * permanent limitation, not a local-environment gap) -- see ADR-0030.
 */
class StorageTransitEncryptionValidationTest {

  private static final Path BOOTSTRAP_PATH =
      Path.of(RepositoryRoot.path("infrastructure/k8s/bootstrap"));

  @Test
  @DisplayName("The FelixConfiguration enabling WireGuard exists, parses, and enables it")
  void felixConfigurationEnablesWireguard() throws Exception {
    Path file = BOOTSTRAP_PATH.resolve("calico-wireguard-config.yaml");
    assertThat(Files.exists(file)).as("%s exists", file).isTrue();

    Yaml yaml = new Yaml();
    Map<String, Object> doc;
    try (InputStream in = new FileInputStream(file.toFile())) {
      doc = yaml.load(in);
    }
    assertThat(doc.get("kind")).isEqualTo("FelixConfiguration");
    @SuppressWarnings("unchecked")
    Map<String, Object> metadata = (Map<String, Object>) doc.get("metadata");
    assertThat(metadata.get("name"))
        .as("FelixConfiguration is a cluster-scoped singleton named 'default'")
        .isEqualTo("default");
    @SuppressWarnings("unchecked")
    Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
    assertThat(spec.get("wireguardEnabled")).isEqualTo(true);
  }

  @Test
  @DisplayName("Bootstrap applies the WireGuard config right after Calico installs")
  void bootstrapAppliesWireguardConfigAfterCalico() throws Exception {
    String upScript = Files.readString(BOOTSTRAP_PATH.resolve("up.sh"));
    int calicoWaitIndex = upScript.indexOf("waiting for Calico");
    int wireguardApplyIndex = upScript.indexOf("calico-wireguard-config.yaml");
    assertThat(calicoWaitIndex).as("Calico's own wait step must exist").isPositive();
    assertThat(wireguardApplyIndex).as("WireGuard config must be applied in up.sh").isPositive();
    assertThat(wireguardApplyIndex)
        .as("WireGuard config must be applied after Calico is confirmed ready, not before")
        .isGreaterThan(calicoWaitIndex);
  }
}

package dev.fondaria.contribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Row 26k: contribution-api runs several replicas, and {@code fondaria_participants_enrolled_total}
 * is a per-pod counter. Without a per-pod {@code service.instance.id} the Collector exports one
 * series the replicas overwrite, and {@code increase()} counts every swing between them as a reset.
 */
class MetricsInstanceIdentityTest {

  @Test
  @SuppressWarnings("unchecked")
  void everyReplicaExportsItsMetricsUnderItsOwnInstanceId() throws Exception {
    Map<String, Object> config;
    try (InputStream in = getClass().getResourceAsStream("/application.yaml")) {
      config = new Yaml().load(in);
    }
    Map<String, Object> management = (Map<String, Object>) config.get("management");
    Map<String, Object> opentelemetry = (Map<String, Object>) management.get("opentelemetry");
    Map<String, Object> attributes = (Map<String, Object>) opentelemetry.get("resource-attributes");

    assertThat(attributes.get("[service.instance.id]")).isEqualTo("${HOSTNAME:local}");
  }
}

package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Rows 26k/26n: a service whose replicas export counters without a per-pod {@code
 * service.instance.id} produces one Prometheus series they overwrite, and {@code rate()} / {@code
 * increase()} count every swing between pods as a counter reset. Every service that may run more
 * than one replica must set it, the way projection-worker does (ADR-0032 §4).
 */
class PerPodMetricIdentityTest {

  /** Every service: any of them can run more than one replica (base, prod overlay or HPA). */
  private static final List<String> SCALED_OUT_SERVICES =
      List.of(
          "allotment-job",
          "amqp-fanout",
          "balance-api",
          "contribution-api",
          "eod-job",
          "projection-worker",
          "statement-worker",
          "valuation-api");

  @Test
  @SuppressWarnings("unchecked")
  void everyScaledOutServiceExportsMetricsUnderItsOwnInstanceId() throws Exception {
    for (String service : SCALED_OUT_SERVICES) {
      Path yaml =
          Path.of(
              RepositoryRoot.path("services/" + service + "/src/main/resources/application.yaml"));
      Map<String, Object> config;
      try (InputStream in = Files.newInputStream(yaml)) {
        config = new Yaml().load(in);
      }
      Map<String, Object> management = (Map<String, Object>) config.get("management");
      Map<String, Object> opentelemetry =
          management == null ? null : (Map<String, Object>) management.get("opentelemetry");
      Map<String, Object> attributes =
          opentelemetry == null
              ? null
              : (Map<String, Object>) opentelemetry.get("resource-attributes");

      assertThat(attributes)
          .as("%s must set management.opentelemetry.resource-attributes", service)
          .isNotNull();
      assertThat(String.valueOf(attributes.get("[service.instance.id]")))
          .as("%s service.instance.id must be per pod", service)
          .contains("HOSTNAME");
    }
  }
}

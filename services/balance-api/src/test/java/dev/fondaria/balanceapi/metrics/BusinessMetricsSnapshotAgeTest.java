package dev.fondaria.balanceapi.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.fondaria.balanceapi.config.BusinessMetricsConfiguration;
import dev.fondaria.balanceapi.support.FakeLedgerPort;
import dev.fondaria.balanceapi.support.FakeNavCache;
import dev.fondaria.ledger.AccountIdDeriver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Business Overview's "Telemetry Snapshot Age" is {@code time() - snapshot_timestamp}: Grafana's
 * wall clock minus the stamp. On the 2026-10-26 GKE run the stamp came from the demo's simulated
 * clock, 15 hours ahead after {@code demo-tools advance}, and a fresh sweep read as −15.1 hours
 * old. The wiring must stamp wall time whatever clock the rest of the service runs on.
 */
class BusinessMetricsSnapshotAgeTest {

  @Test
  @DisplayName("the configured publisher stamps its sweep with wall-clock time")
  void snapshotTimestampIsWallTime() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    BusinessMetricsPublisher publisher =
        new BusinessMetricsConfiguration()
            .businessMetricsPublisher(
                registry,
                List::of,
                new FakeNavCache(),
                new FakeLedgerPort(),
                new AccountIdDeriver(UUID.randomUUID()),
                mock(JdbcClient.class));

    long before = Instant.now().getEpochSecond();
    publisher.publishMetrics();
    long after = Instant.now().getEpochSecond();

    double stamp =
        registry.get("fondaria_business_metrics_snapshot_timestamp_seconds").gauge().value();
    assertThat((long) stamp).isBetween(before, after);
  }
}

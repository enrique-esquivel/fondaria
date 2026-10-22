package dev.fondaria.testing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton fixture for a Tempo container (Task 11.2), for the one cross-service test that proves a
 * trace actually spans HTTP ingress → ledger → CDC → projection in a real trace backend.
 *
 * <p><b>Services point at this container's own OTLP receiver directly, never at a Collector.</b>
 * Production always goes through the Collector (Task 11.1, {@code otel-collector/config.yaml}) —
 * applications must never know about Tempo directly — but that fan-out is Task 11.1's own concern
 * and already proven there. What this fixture exists to prove is the application-level trace
 * <i>propagation</i> logic (Task 11.2's {@code TraceCorrelationPublisher}/{@code
 * TraceCorrelationReader}), and Tempo's distributor accepts OTLP on the same ports a Collector
 * would forward to (see {@code tempo/tempo.yaml}'s own {@code distributor.receivers.otlp}), so
 * pointing a test directly at it is a faithful, simpler proof of exactly that.
 *
 * <p>Config is supplied inline rather than mounted from a repository path, so this fixture has no
 * dependency on where the test happens to run from — a minimal single-binary config, the same shape
 * as {@code infrastructure/observability/tempo/tempo.yaml} but pared to what a test needs (no
 * retention tuning, since nothing here runs long enough to matter).
 */
public final class TempoFixture {

  private static final Logger LOGGER = LoggerFactory.getLogger(TempoFixture.class);
  private static volatile TempoFixture instance;

  private static final String CONFIG =
      """
      server:
        http_listen_port: 3200
      distributor:
        receivers:
          otlp:
            protocols:
              grpc:
                endpoint: 0.0.0.0:4317
              http:
                endpoint: 0.0.0.0:4318
      ingester:
        # Longer than the real gap between a trace's first and last span: a cross-service
        # trace's spans arrive several seconds apart (HTTP ingress, then a real CDC hop to
        # projection-worker). A short idle period cuts the trace into a completed block after
        # only the first service's spans arrive, and the later span then waits for a
        # separate flush cycle before the two halves are jointly queryable by trace id --
        # measured empirically at multiple minutes with a 1s idle period, against a same-trace
        # gap of order 5s.
        trace_idle_period: 20s
        max_block_duration: 30s
      compactor:
        compaction:
          block_retention: 1h
      storage:
        trace:
          backend: local
          local:
            path: /var/tempo/traces
          wal:
            path: /var/tempo/wal
          # The querier discovers a newly-completed block by polling the backend on this
          # interval -- default is minutes, tuned for an object store nobody expects
          # sub-minute freshness from. A local test fixture needs a completed block to be
          # queryable within seconds of the ingester flushing it, not minutes later.
          blocklist_poll: 2s
      """;

  private final GenericContainer<?> container;

  private TempoFixture() {
    ContainerImages.initDockerEnv();
    LOGGER.info("Starting Tempo container with image {}", ContainerImages.TEMPO);
    this.container =
        new GenericContainer<>(DockerImageName.parse(ContainerImages.TEMPO))
            .withExposedPorts(3200, 4317, 4318)
            .withCopyToContainer(Transferable.of(CONFIG), "/etc/tempo.yaml")
            .withCommand("-config.file=/etc/tempo.yaml")
            .withReuse(true)
            .waitingFor(Wait.forListeningPorts(3200, 4317, 4318))
            .withLogConsumer(new Slf4jLogConsumer(LOGGER));
    this.container.start();
    LOGGER.info(
        "Tempo container started, query API at {}:{}, OTLP gRPC at {}:{}",
        getHost(),
        getQueryApiPort(),
        getHost(),
        getOtlpGrpcPort());
  }

  public static TempoFixture getInstance() {
    if (instance == null) {
      synchronized (TempoFixture.class) {
        if (instance == null) {
          instance = new TempoFixture();
        }
      }
    }
    return instance;
  }

  public String getHost() {
    return container.getHost();
  }

  public int getQueryApiPort() {
    return container.getMappedPort(3200);
  }

  public int getOtlpGrpcPort() {
    return container.getMappedPort(4317);
  }

  public int getOtlpHttpPort() {
    return container.getMappedPort(4318);
  }

  /** {@code http://host:port}, the base URL a service's OTLP exporter should be pointed at. */
  public String getOtlpHttpEndpoint() {
    return "http://" + getHost() + ":" + getOtlpHttpPort();
  }

  /** {@code http://host:port}, the base URL Tempo's own query API answers on. */
  public String getQueryApiUrl() {
    return "http://" + getHost() + ":" + getQueryApiPort();
  }
}

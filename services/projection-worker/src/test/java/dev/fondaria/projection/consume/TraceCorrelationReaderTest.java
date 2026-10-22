package dev.fondaria.projection.consume;

import static org.assertj.core.api.Assertions.assertThatCode;

import dev.fondaria.testing.RedisFixture;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Against a real Redis (Task 11.2): a miss and a corrupted entry must both degrade to a safe,
 * unlinked span rather than throwing out of {@link JdbcProjectionSink#accept}.
 *
 * <p>{@link Tracer#NOOP} stands in for the tracer throughout: every case here is decided by {@link
 * TraceCorrelationReader#continueTrace} before it ever calls a tracer method (a miss returns before
 * {@code parse()}; a malformed entry is rejected by {@code parse()}'s own shape check, or caught if
 * it somehow reaches the tracer). The one path that does reach a real tracer — a well-formed entry
 * producing a genuinely linked child span — is proven end to end, against a real trace backend, by
 * {@code CdcBoundaryTraceEndToEndTest} in {@code libs/e2e-tests}.
 */
@Tag("integration")
class TraceCorrelationReaderTest {

  private static RedisClient client;
  private static TraceCorrelationReader reader;
  private static SimpleMeterRegistry meterRegistry;

  @BeforeAll
  static void connect() {
    RedisFixture redis = RedisFixture.getInstance();
    client = RedisClient.create(redis.getRedisUrl());
    meterRegistry = new SimpleMeterRegistry();
    reader = new TraceCorrelationReader(client, Tracer.NOOP, new RedisMetrics(meterRegistry));
  }

  @AfterAll
  static void shutdown() {
    reader.close();
  }

  @Test
  @DisplayName("no entry for this transfer is a safe miss")
  void missIsSafe() {
    String transferId = "no-such-transfer-" + UUID.randomUUID();

    assertThatCode(() -> reader.continueTrace(transferId, "test.span").close())
        .doesNotThrowAnyException();
    org.assertj.core.api.Assertions.assertThat(
            meterRegistry
                .get("redis_request_duration_seconds")
                .tag("operation", "find")
                .timer()
                .count())
        .isPositive();
  }

  @Test
  @DisplayName("an entry that is not a traceparent at all is a safe miss, not a decode failure")
  void notATraceparentIsSafe() {
    String transferId = freshTransferId();
    seed(transferId, "not-a-traceparent-at-all");

    assertThatCode(() -> reader.continueTrace(transferId, "test.span").close())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a traceparent with the right shape but the wrong id lengths is also a safe miss")
  void wrongLengthIdsAreSafe() {
    String transferId = freshTransferId();
    seed(transferId, "00-tooshort-alsotooshort-01");

    assertThatCode(() -> reader.continueTrace(transferId, "test.span").close())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("100 calls against unreachable Valkey complete in < 1s and log exactly one WARN")
  void unreachableValkeyBacksOffPromptlyAndLogsOnce() throws Exception {
    int port;
    try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
      port = ss.getLocalPort();
    }
    RedisClient unreachableClient = RedisClient.create("redis://localhost:" + port);
    unreachableClient.setDefaultTimeout(java.time.Duration.ofMillis(200));

    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(TraceCorrelationReader.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> listAppender =
        new ch.qos.logback.core.read.ListAppender<>();
    listAppender.start();
    logger.addAppender(listAppender);

    try (TraceCorrelationReader backingOffReader =
        new TraceCorrelationReader(
            unreachableClient, Tracer.NOOP, new RedisMetrics(new SimpleMeterRegistry()))) {
      org.assertj.core.api.Assertions.assertThat(backingOffReader.backoffState()).isEqualTo(0);

      // 100 continueTrace calls against unreachable Valkey
      long startNanos = System.nanoTime();
      for (int i = 0; i < 100; i++) {
        backingOffReader.continueTrace("transfer-" + i, "projection.transfer").close();
      }
      long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

      // Must complete in well under 1 s total (first call times out ~200ms, subsequent 99 are
      // instant)
      org.assertj.core.api.Assertions.assertThat(elapsedMillis)
          .as("100 continueTrace calls should complete in < 1s")
          .isLessThan(1000L);

      // Backoff gauge must report 1 (backed off)
      org.assertj.core.api.Assertions.assertThat(backingOffReader.backoffState()).isEqualTo(1);

      // Exactly one WARN logged across those 100 calls
      long warnCount =
          listAppender.list.stream()
              .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
              .count();
      org.assertj.core.api.Assertions.assertThat(warnCount)
          .as("Exactly one WARN should be logged across the 100 calls")
          .isEqualTo(1);
    } finally {
      logger.detachAppender(listAppender);
      unreachableClient.shutdown();
    }
  }

  @Test
  @DisplayName("recovering after backoff logs INFO and resets backoffState to 0")
  void recoveryAfterBackoffResetsStateAndLogsInfo() {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(TraceCorrelationReader.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> listAppender =
        new ch.qos.logback.core.read.ListAppender<>();
    listAppender.start();
    logger.addAppender(listAppender);

    // Mock clock to control backoff expiry
    java.util.concurrent.atomic.AtomicReference<java.time.Instant> clockTime =
        new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.now());
    java.time.Clock mockClock =
        new java.time.Clock() {
          @Override
          public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
          }

          @Override
          public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public java.time.Instant instant() {
            return clockTime.get();
          }
        };

    java.util.concurrent.atomic.AtomicBoolean failConnect =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    RedisFixture redis = RedisFixture.getInstance();
    RedisClient recoveryClient = RedisClient.create(redis.getRedisUrl());

    try (TraceCorrelationReader testReader =
        new TraceCorrelationReader(
            recoveryClient,
            Tracer.NOOP,
            java.time.Duration.ofSeconds(30),
            mockClock,
            () -> {
              if (failConnect.get()) {
                throw new io.lettuce.core.RedisConnectionException("Simulated connection failure");
              }
              return recoveryClient.connect();
            },
            new RedisMetrics(new SimpleMeterRegistry()))) {
      // First attempt fails and enters backoff
      testReader.continueTrace("1", "span").close();
      org.assertj.core.api.Assertions.assertThat(testReader.backoffState()).isEqualTo(1);

      long warnCount =
          listAppender.list.stream()
              .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
              .count();
      org.assertj.core.api.Assertions.assertThat(warnCount).isEqualTo(1);

      // Subsequent calls during backoff do not invoke supplier and do not log
      testReader.continueTrace("1", "span").close();
      testReader.continueTrace("1", "span").close();
      org.assertj.core.api.Assertions.assertThat(
              listAppender.list.stream()
                  .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                  .count())
          .isEqualTo(1);

      // Advance clock past backoff duration and allow connect to succeed
      clockTime.set(clockTime.get().plus(java.time.Duration.ofSeconds(31)));
      failConnect.set(false);

      // Successful connect recovers
      testReader.continueTrace("2", "span").close();
      org.assertj.core.api.Assertions.assertThat(testReader.backoffState()).isEqualTo(0);

      // INFO logged on reconnection
      long infoCount =
          listAppender.list.stream()
              .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO)
              .filter(e -> e.getFormattedMessage().contains("Connected to trace correlation Redis"))
              .count();
      org.assertj.core.api.Assertions.assertThat(infoCount).isEqualTo(1);
    } finally {
      logger.detachAppender(listAppender);
    }
  }

  private static String freshTransferId() {
    return String.valueOf(Math.abs(UUID.randomUUID().getLeastSignificantBits()));
  }

  private void seed(String transferId, String traceparent) {
    try (StatefulRedisConnection<String, String> connection = client.connect()) {
      connection.sync().set(TraceCorrelationReader.KEY_PREFIX + transferId, traceparent);
    }
  }
}

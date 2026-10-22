package dev.fondaria.statementworker.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.testing.RedisFixture;
import io.lettuce.core.RedisClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link RedisCompletionCounter} against a real Redis (Task 7.5b, {@code ADR-0022} §3): the one
 * property the fan-out's "done when count == expected" signal depends on — a redelivered completion
 * for the same participant must never be counted twice.
 */
@Tag("integration")
class RedisCompletionCounterIntegrationTest {

  private RedisCompletionCounter counter;
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    RedisFixture redis = RedisFixture.getInstance();
    RedisClient client =
        RedisClient.create(
            "redis://:" + redis.getPassword() + "@" + redis.getHost() + ":" + redis.getPort());
    meterRegistry = new SimpleMeterRegistry();
    counter =
        new RedisCompletionCounter(client, Duration.ofMinutes(5), new RedisMetrics(meterRegistry));
  }

  @AfterEach
  void tearDown() {
    counter.close();
  }

  @Test
  @DisplayName(
      "recording the same participant twice — a redelivered job — increments the count only once")
  void redeliveryIsIdempotent() {
    String period = "2026-" + UUID.randomUUID().toString().substring(0, 4);
    String participant = "org:ref-" + UUID.randomUUID();

    counter.recordCompleted(period, participant);
    var second = counter.recordCompleted(period, participant);

    assertThat(second).hasValue(1L);
    assertThat(counter.currentCount(period)).hasValue(1L);
    assertThat(
            meterRegistry
                .get("redis_request_duration_seconds")
                .tag("operation", "recordCompleted")
                .timer()
                .count())
        .isEqualTo(2L);
  }

  @Test
  @DisplayName("distinct participants each increment the count")
  void distinctParticipantsEachIncrement() {
    String period = "2026-" + UUID.randomUUID().toString().substring(0, 4);

    counter.recordCompleted(period, "org:ref-a-" + UUID.randomUUID());
    var afterSecond = counter.recordCompleted(period, "org:ref-b-" + UUID.randomUUID());

    assertThat(afterSecond).hasValue(2L);
  }

  @Test
  @DisplayName("a period with nothing recorded yet reads as zero, not empty")
  void unrecordedPeriodReadsAsZero() {
    String period = "2026-" + UUID.randomUUID().toString().substring(0, 4);
    assertThat(counter.currentCount(period)).hasValue(0L);
  }
}

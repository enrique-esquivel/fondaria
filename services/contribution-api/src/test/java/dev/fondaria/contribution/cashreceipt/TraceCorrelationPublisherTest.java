package dev.fondaria.contribution.cashreceipt;

import static org.assertj.core.api.Assertions.assertThatCode;

import dev.fondaria.ledger.TransferId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code null} stands in for the Redis connection throughout: with no current span, {@link
 * TraceCorrelationPublisher#publish} must return before ever dereferencing it, exactly as it does
 * whenever there is genuinely nothing to correlate (no incoming request context, an un-sampled
 * span). A connection this class would actually write to belongs to the integration tier, alongside
 * a real Redis — {@code CashReceiptWithoutRedisIntegrationTest} already exercises this class's
 * error-swallowing path against a real, stopped one.
 */
class TraceCorrelationPublisherTest {

  private final TraceCorrelationPublisher publisher =
      new TraceCorrelationPublisher(
          null, Tracer.NOOP, Duration.ofMinutes(5), new RedisMetrics(new SimpleMeterRegistry()));

  @Test
  @DisplayName("with no current span, publishing never touches the connection")
  void noCurrentSpanNeverTouchesTheConnection() {
    assertThatCode(() -> publisher.publish(TransferId.of(1L, 2L))).doesNotThrowAnyException();
  }
}

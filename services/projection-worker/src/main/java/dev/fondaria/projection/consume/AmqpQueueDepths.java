package dev.fondaria.projection.consume;

import dev.fondaria.projection.config.ActivityProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.stereotype.Component;

/**
 * {@link QueueDepths} through a passive {@code queue.declare} per queue, which answers with the
 * queue's ready-message count and changes nothing.
 *
 * <p>The client's own RPC timeout is ten minutes, so the reads run on a virtual thread and are
 * abandoned after {@code fondaria.projection.activity.depth-timeout} (docs/ENGINEERING-RULES.md §4:
 * every external call has an explicit timeout). An abandoned read is interrupted; the summary is
 * logged without depths.
 */
@Component
public class AmqpQueueDepths implements QueueDepths {

  private static final Logger LOGGER = LoggerFactory.getLogger(AmqpQueueDepths.class);

  private final AmqpAdmin admin;
  private final Duration timeout;

  public AmqpQueueDepths(AmqpAdmin admin, ActivityProperties properties) {
    this.admin = admin;
    this.timeout = properties.depthTimeout();
  }

  @Override
  public Map<String, Long> ready(List<String> queues) {
    CompletableFuture<Map<String, Long>> reads = new CompletableFuture<>();
    Thread reader =
        Thread.ofVirtual()
            .name("projection-queue-depth")
            .start(
                () -> {
                  try {
                    reads.complete(readAll(queues));
                  } catch (RuntimeException e) {
                    reads.completeExceptionally(e);
                  }
                });
    try {
      return reads.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      reader.interrupt();
      LOGGER.warn("queue depths not read within {}", timeout);
    } catch (ExecutionException e) {
      // A broker message can carry a hostname (docs/ENGINEERING-RULES.md §7): log the type only.
      LOGGER.warn("queue depths not read: {}", e.getCause().getClass().getSimpleName());
    } catch (InterruptedException e) {
      reader.interrupt();
      Thread.currentThread().interrupt();
    }
    return Map.of();
  }

  private Map<String, Long> readAll(List<String> queues) {
    Map<String, Long> depths = new LinkedHashMap<>();
    for (String queue : queues) {
      QueueInformation info = admin.getQueueInfo(queue);
      if (info != null) {
        depths.put(queue, (long) info.getMessageCount());
      }
    }
    return depths;
  }
}

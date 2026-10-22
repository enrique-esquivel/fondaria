package dev.fondaria.projection.config;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The per-consumer checkpoint's tuning (ADR-0032 §1), bound under {@code
 * fondaria.projection.checkpoint}.
 *
 * @param instanceId {@code PROJECTION_INSTANCE_ID} — this process's name in {@code consumer_id}
 *     ({@code <instanceId>/<consumer tag>}). The Deployment sets it to the pod name (downward API).
 *     Blank (a bare local JVM) falls back to a random id: the consumer tag already makes the row
 *     unique; the instance id is there so an operator can read which pod a row belongs to.
 * @param activeWindow {@code PROJECTION_CHECKPOINT_ACTIVE_WINDOW} — a consumer that advanced within
 *     this window counts toward the head ({@code MIN}); an idle one does not, or the quietest shard
 *     would pin the head. Must exceed the transient-retry backoff ceiling. Defaults to 5 min.
 * @param retention {@code PROJECTION_CHECKPOINT_RETENTION} — rows idle longer than this are
 *     deleted. Must exceed {@code activeWindow}. Defaults to 1 h.
 */
@ConfigurationProperties(prefix = "fondaria.projection.checkpoint")
public record CheckpointProperties(String instanceId, Duration activeWindow, Duration retention) {

  public CheckpointProperties {
    instanceId =
        instanceId == null || instanceId.isBlank()
            ? "instance-" + UUID.randomUUID()
            : instanceId.strip();
    activeWindow = activeWindow == null ? Duration.ofMinutes(5) : activeWindow;
    retention = retention == null ? Duration.ofHours(1) : retention;
    if (activeWindow.isNegative() || activeWindow.isZero()) {
      throw new ConfigurationException(
          "PROJECTION_CHECKPOINT_ACTIVE_WINDOW must be positive",
          "Set PROJECTION_CHECKPOINT_ACTIVE_WINDOW to a positive duration, e.g. 5m.");
    }
    if (retention.compareTo(activeWindow) <= 0) {
      throw new ConfigurationException(
          "PROJECTION_CHECKPOINT_RETENTION must exceed PROJECTION_CHECKPOINT_ACTIVE_WINDOW",
          "An active consumer's row must never be pruned. See ADR-0032 §1.");
    }
  }

  /**
   * ADR-0032 §1: a consumer sleeping in one ordinary transient-retry backoff must not drop out of
   * the head, so the active window must exceed the backoff ceiling.
   */
  public void requireActiveWindowExceeds(Duration retryMaxBackoff) {
    if (activeWindow.compareTo(retryMaxBackoff) <= 0) {
      throw new ConfigurationException(
          "PROJECTION_CHECKPOINT_ACTIVE_WINDOW must exceed PROJECTION_RETRY_MAX_BACKOFF",
          "Raise PROJECTION_CHECKPOINT_ACTIVE_WINDOW (default 5m) above the retry ceiling.");
    }
  }
}

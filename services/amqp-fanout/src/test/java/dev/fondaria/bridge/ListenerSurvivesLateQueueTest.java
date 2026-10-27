package dev.fondaria.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The bridge must keep waiting for {@code bridge.ledger} rather than give up on it. The queue is
 * declared by the {@code rabbitmq-topology} Job, which can finish after this service starts. With
 * {@code missing-queues-fatal: true}, Spring stops the listener container for good after a few
 * attempts while the pod stays Ready: every ledger event then piles up unread and the projection
 * silently stops. That happened on the first GKE run (ADR-0036); on kind the Job happened to win.
 */
class ListenerSurvivesLateQueueTest {

  @Test
  @SuppressWarnings("unchecked")
  void aMissingQueueAtStartupIsNotFatal() throws Exception {
    Map<String, Object> config;
    try (InputStream in = getClass().getResourceAsStream("/application.yaml")) {
      config = new Yaml().load(in);
    }
    Map<String, Object> simple =
        (Map<String, Object>)
            ((Map<String, Object>)
                    ((Map<String, Object>)
                            ((Map<String, Object>) config.get("spring")).get("rabbitmq"))
                        .get("listener"))
                .get("simple");
    assertThat(simple.get("missing-queues-fatal")).isEqualTo(false);
  }
}

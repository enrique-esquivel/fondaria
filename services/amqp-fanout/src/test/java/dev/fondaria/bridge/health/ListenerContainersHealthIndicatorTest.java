package dev.fondaria.bridge.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.boot.actuate.health.Status;

class ListenerContainersHealthIndicatorTest {

  @Test
  void upWhileTheListenerContainerRuns() {
    ListenerContainersHealthIndicator indicator =
        new ListenerContainersHealthIndicator(() -> List.of(container(true)));

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  }

  /** The GKE failure (ADR-0036): the container stopped for good while the pod stayed Ready. */
  @Test
  void downOnceTheListenerContainerHasStopped() {
    ListenerContainersHealthIndicator indicator =
        new ListenerContainersHealthIndicator(() -> List.of(container(false)));

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    assertThat(indicator.health().getDetails()).containsEntry("stopped", 1L);
  }

  @Test
  void downWhenNoListenerContainerIsRegistered() {
    ListenerContainersHealthIndicator indicator = new ListenerContainersHealthIndicator(List::of);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void followsTheContainerBackUpAfterARestart() {
    MessageListenerContainer container = mock(MessageListenerContainer.class);
    when(container.isRunning()).thenReturn(false, true);
    ListenerContainersHealthIndicator indicator =
        new ListenerContainersHealthIndicator(() -> List.of(container));

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  }

  private static MessageListenerContainer container(boolean running) {
    MessageListenerContainer container = mock(MessageListenerContainer.class);
    when(container.isRunning()).thenReturn(running);
    return container;
  }
}

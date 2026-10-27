package dev.fondaria.projection.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.boot.actuate.health.Status;

class ListenerContainersHealthIndicatorTest {

  @Test
  void upWhileEveryContainerRuns() {
    ListenerContainersHealthIndicator indicator =
        new ListenerContainersHealthIndicator(
            () -> List.of(container(true), container(true), container(true)));

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  }

  /** One entries queue's container stopped: that shard is consumed by nobody on this pod. */
  @Test
  void downWhenOneContainerHasStopped() {
    ListenerContainersHealthIndicator indicator =
        new ListenerContainersHealthIndicator(
            () -> List.of(container(true), container(false), container(true)));

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    assertThat(indicator.health().getDetails()).containsEntry("stopped", 1L);
  }

  /** Before ClusterHalt opens the startup gate no container runs, and the pod is not Ready. */
  @Test
  void downBeforeTheContainersAreStarted() {
    ListenerContainersHealthIndicator indicator =
        new ListenerContainersHealthIndicator(() -> List.of(container(false), container(false)));

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void downWhenNoListenerContainerIsRegistered() {
    ListenerContainersHealthIndicator indicator = new ListenerContainersHealthIndicator(List::of);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
  }

  private static MessageListenerContainer container(boolean running) {
    MessageListenerContainer container = mock(MessageListenerContainer.class);
    when(container.isRunning()).thenReturn(running);
    return container;
  }
}

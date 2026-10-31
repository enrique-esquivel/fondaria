package dev.fondaria.projection.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;

/** Entries queues' containers parked and unparked for the rebalance (ADR-0042). */
class ListenerContainersTest {

  private static final String QUEUE = "projection.entries.v1.3";

  private final RabbitListenerEndpointRegistry registry =
      mock(RabbitListenerEndpointRegistry.class);
  private final MessageListenerContainer transfers = mock(MessageListenerContainer.class);
  private final MessageListenerContainer entries = mock(MessageListenerContainer.class);
  private final ListenerContainers containers = new ListenerContainers(registry);

  @BeforeEach
  void twoContainers() {
    when(registry.getListenerContainers()).thenReturn(List.of(transfers, entries));
    when(registry.getListenerContainer(EntriesQueueListeners.ENDPOINT_ID_PREFIX + QUEUE))
        .thenReturn(entries);
    when(entries.isRunning()).thenReturn(true);
  }

  @Test
  void aParkedContainerIsStoppedAndLeftOutOfReadiness() {
    assertThat(containers.park(QUEUE)).isTrue();

    verify(entries).stop(any(Runnable.class));
    assertThat(containers.expectedRunning()).containsExactly(transfers);
  }

  @Test
  void unparkingStartsItAgainAndCountsItForReadiness() {
    containers.park(QUEUE);
    when(entries.isRunning()).thenReturn(false);

    assertThat(containers.unparkAll()).isEqualTo(1);

    verify(entries).start();
    assertThat(containers.expectedRunning()).containsExactly(transfers, entries);
    assertThat(containers.unparkAll()).isZero();
  }

  @Test
  void aContainerThatIsNotRunningIsNotParked() {
    when(entries.isRunning()).thenReturn(false);

    assertThat(containers.park(QUEUE)).isFalse();

    verify(entries, never()).stop(any(Runnable.class));
    assertThat(containers.expectedRunning()).containsExactly(transfers, entries);
  }

  @Test
  void anUnknownQueueIsNotParked() {
    assertThat(containers.park("not.an.entries.queue")).isFalse();
  }

  @Test
  void aQueueAlreadyParkedIsNotStoppedTwice() {
    containers.park(QUEUE);

    assertThat(containers.park(QUEUE)).isFalse();

    verify(entries).stop(any(Runnable.class));
  }
}

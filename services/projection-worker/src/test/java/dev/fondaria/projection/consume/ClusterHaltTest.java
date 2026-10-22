package dev.fondaria.projection.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.fondaria.projection.config.HaltProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.scheduling.TaskScheduler;

/** {@link ClusterHalt}'s decisions, with the flag, the containers and the scheduler faked. */
class ClusterHaltTest {

  private final ClusterHaltFlag flag = mock(ClusterHaltFlag.class);
  private final ListenerContainers containers = mock(ListenerContainers.class);
  private final TaskScheduler scheduler = mock(TaskScheduler.class);
  private final ProjectionHalt halt = new ProjectionHalt();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private ClusterHalt clusterHalt;

  @BeforeEach
  void wire() {
    clusterHalt =
        new ClusterHalt(
            flag, halt, containers, scheduler, new HaltProperties(null, null, null), meters);
  }

  private static ClusterHaltFlag.ActiveHalt someHalt() {
    return new ClusterHaltFlag.ActiveHalt(7, "pod-a", "MALFORMED_EVENT", "projection.entries.v1.3");
  }

  @Test
  void anActiveHaltFromAnotherReplicaTripsTheLatchAndStopsTheContainers() {
    when(flag.active()).thenReturn(Optional.of(someHalt()));

    clusterHalt.poll();

    assertThat(halt.isHalted()).isTrue();
    assertThat(halt.reason().orElseThrow()).contains("cluster halt 7", "pod-a", "MALFORMED_EVENT");
    verify(containers).stopAll();
    verify(containers, never()).startAll();
  }

  @Test
  void pollingAnAlreadyLatchedPodDoesNotStopTheContainersAgain() {
    halt.trigger("local poison");
    when(flag.active()).thenReturn(Optional.of(someHalt()));

    clusterHalt.poll();
    clusterHalt.poll();

    verify(containers, never()).stopAll();
    assertThat(halt.reason().orElseThrow()).isEqualTo("local poison");
  }

  @Test
  void theFirstPollFindingNoHaltStartsTheConsumersExactlyOnce() {
    when(flag.active()).thenReturn(Optional.empty());

    clusterHalt.poll();
    clusterHalt.poll();
    clusterHalt.poll();

    verify(containers, times(1)).startAll();
    assertThat(halt.isHalted()).isFalse();
  }

  @Test
  void aLatchedPodNeverStartsItsConsumers() {
    halt.trigger("local poison");
    when(flag.active()).thenReturn(Optional.empty()); // e.g. the operator already cleared the row

    clusterHalt.poll();

    verify(containers, never()).startAll();
  }

  @Test
  void aFailedPollChangesNothing() {
    when(flag.active()).thenThrow(new QueryTimeoutException("slow"));

    clusterHalt.poll();

    assertThat(halt.isHalted()).isFalse();
    verify(containers, never()).startAll();
    verify(containers, never()).stopAll();
  }

  @Test
  void aFailedPollBeforeStartupLeavesTheGateClosedUntilOneSucceeds() {
    when(flag.active()).thenThrow(new QueryTimeoutException("slow")).thenReturn(Optional.empty());

    clusterHalt.poll();
    verify(containers, never()).startAll();

    clusterHalt.poll();
    verify(containers, times(1)).startAll();
  }

  @Test
  void aFailedStartIsRetriedOnTheNextPoll() {
    when(flag.active()).thenReturn(Optional.empty());
    doThrow(new IllegalStateException("broker down")).doNothing().when(containers).startAll();

    clusterHalt.poll();
    clusterHalt.poll();

    verify(containers, times(2)).startAll();
  }

  @Test
  void raiseInsertsTheFlagOnce() {
    when(flag.raise("MALFORMED_EVENT", "q")).thenReturn(true);

    clusterHalt.raise("MALFORMED_EVENT", "q");

    verify(flag).raise("MALFORMED_EVENT", "q");
    verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
  }

  @Test
  void raiseThatCannotReachTheDatabaseNeverThrowsAndRetriesUntilItLands() {
    when(flag.raise(anyString(), anyString()))
        .thenThrow(new QueryTimeoutException("db down"))
        .thenThrow(new QueryTimeoutException("db down"))
        .thenReturn(true);

    clusterHalt.raise("UNKNOWN_SCHEMA_VERSION", "q");

    ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).schedule(retry.capture(), any(Instant.class));
    retry.getValue().run(); // second attempt fails too, and schedules a third

    ArgumentCaptor<Runnable> retryAgain = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler, times(2)).schedule(retryAgain.capture(), any(Instant.class));
    retryAgain.getAllValues().get(1).run(); // third attempt lands

    verify(flag, times(3)).raise(eq("UNKNOWN_SCHEMA_VERSION"), eq("q"));
    verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
  }

  @Test
  void theHaltedGaugeFollowsTheLatch() {
    assertThat(meters.get(ClusterHalt.HALTED_GAUGE).gauge().value()).isZero();

    halt.trigger("poison");

    assertThat(meters.get(ClusterHalt.HALTED_GAUGE).gauge().value()).isEqualTo(1.0);
  }

  @Test
  void theGateReportsClosedUntilTheConsumersStart() {
    when(flag.active()).thenThrow(new QueryTimeoutException("slow")).thenReturn(Optional.empty());

    clusterHalt.poll();
    assertThat(clusterHalt.consumersStarted()).isFalse();

    clusterHalt.poll();
    assertThat(clusterHalt.consumersStarted()).isTrue();
  }

  @Test
  void aPollRunningDuringShutdownDoesNotStartConsumers() {
    when(flag.active()).thenReturn(Optional.empty());

    clusterHalt.stop();
    clusterHalt.poll();

    verify(containers, never()).startAll();
  }
}

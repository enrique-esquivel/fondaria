package dev.fondaria.projection.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.fondaria.projection.config.ProjectionConsumerProperties;
import dev.fondaria.projection.config.RebalanceProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

/**
 * {@link EntriesQueueRebalancer}'s decision (ADR-0042), with the broker's consumer count, this
 * pod's ownership and its containers faked. Defaults: 16 entries queues, 4 transfer consumers per
 * pod, so 8 transfers consumers mean 2 pods and a share of 8.
 */
class EntriesQueueRebalancerTest {

  private static final List<String> ALL_16 =
      IntStream.range(0, 16).mapToObj(i -> "projection.entries.v1." + i).toList();

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final ProjectionActivity activity = mock(ProjectionActivity.class);
  private final ListenerContainers containers = mock(ListenerContainers.class);
  private final TaskScheduler scheduler = mock(TaskScheduler.class);
  private final ProjectionHalt halt = new ProjectionHalt();
  private final Deque<OptionalInt> consumerReads = new ArrayDeque<>();
  private final List<String> countedQueues = new ArrayList<>();
  private final List<String> parked = new ArrayList<>();

  private final EntriesQueueRebalancer rebalancer =
      new EntriesQueueRebalancer(
          activity,
          containers,
          queue -> {
            countedQueues.add(queue);
            return consumerReads.isEmpty() ? OptionalInt.empty() : consumerReads.pop();
          },
          halt,
          scheduler,
          defaultConsumerProperties(),
          new RebalanceProperties(null),
          registry);

  @BeforeEach
  void parkingSucceedsOncePerQueue() {
    when(containers.park(anyString()))
        .thenAnswer(
            call -> {
              String queue = call.getArgument(0);
              if (parked.contains(queue)) {
                return false;
              }
              parked.add(queue);
              return true;
            });
  }

  @Test
  void theShareIsTheCeilingOfQueuesOverLivePods() {
    assertThat(EntriesQueueRebalancer.fairShare(16, 1)).isEqualTo(16);
    assertThat(EntriesQueueRebalancer.fairShare(16, 2)).isEqualTo(8);
    assertThat(EntriesQueueRebalancer.fairShare(16, 3)).isEqualTo(6);
    assertThat(EntriesQueueRebalancer.fairShare(16, 0)).isEqualTo(16);
  }

  /** A pod whose transfers consumers are partly registered counts as absent: the safe direction. */
  @Test
  void livePodsRoundDownAndNeverFallBelowOne() {
    assertThat(EntriesQueueRebalancer.livePods(8, 4)).isEqualTo(2);
    assertThat(EntriesQueueRebalancer.livePods(7, 4)).isEqualTo(1);
    assertThat(EntriesQueueRebalancer.livePods(0, 4)).isEqualTo(1);
  }

  @Test
  void aPodOwningEveryQueueOfTwoReleasesTheLastHalfOnTheConfirmingPass() {
    when(activity.ownedEntriesQueues()).thenReturn(ALL_16);
    reads(8, 8);

    rebalancer.rebalance();
    assertThat(parked).isEmpty();

    rebalancer.rebalance();
    assertThat(parked).containsExactlyElementsOf(ALL_16.subList(8, 16));
    assertThat(releases()).isEqualTo(8.0);
    assertThat(countedQueues).containsOnly("projection.transfers.v1");
  }

  /**
   * At its share a pod releases nothing but parks its standbys, so it no longer competes for the
   * queues another pod releases: the kind run's back-and-forth between two over-share pods.
   */
  @Test
  void aPodAtItsShareParksItsStandbysAndReleasesNothing() {
    when(activity.ownedEntriesQueues()).thenReturn(ALL_16.subList(0, 8));
    reads(8, 8);

    rebalancer.rebalance();
    rebalancer.rebalance();

    assertThat(parked).containsExactlyElementsOf(ALL_16.subList(8, 16));
    assertThat(releases()).isZero();
  }

  /** Under its share, a pod stands by for every parked queue again: how a dead pod is replaced. */
  @Test
  void aPodUnderItsShareUnparksOnASingleRead() {
    when(activity.ownedEntriesQueues()).thenReturn(ALL_16.subList(0, 8));
    reads(4);

    rebalancer.rebalance();

    verify(containers).unparkAll();
    verify(containers, never()).park(anyString());
  }

  /** A surge pod appears for one read: the reads disagree, so nothing is parked. */
  @Test
  void twoReadsThatDisagreeParkNothing() {
    when(activity.ownedEntriesQueues()).thenReturn(ALL_16);
    reads(8, 12);

    rebalancer.rebalance();
    rebalancer.rebalance();

    verify(containers, never()).park(anyString());
  }

  @Test
  void anUnreadableCountSkipsThePassAndStartsTheConfirmationOver() {
    when(activity.ownedEntriesQueues()).thenReturn(ALL_16);
    consumerReads.add(OptionalInt.of(8));
    consumerReads.add(OptionalInt.empty());
    consumerReads.add(OptionalInt.of(8));

    rebalancer.rebalance();
    rebalancer.rebalance();
    rebalancer.rebalance();

    verifyNoInteractions(containers);
  }

  @Test
  void aHaltedPodNeitherReadsNorActs() {
    halt.trigger("test");
    reads(8, 8);

    rebalancer.rebalance();
    rebalancer.rebalance();

    assertThat(countedQueues).isEmpty();
    verifyNoInteractions(containers);
  }

  @Test
  void aZeroIntervalSchedulesNothing() {
    EntriesQueueRebalancer disabled =
        new EntriesQueueRebalancer(
            activity,
            containers,
            queue -> OptionalInt.empty(),
            halt,
            scheduler,
            defaultConsumerProperties(),
            new RebalanceProperties(Duration.ZERO),
            new SimpleMeterRegistry());

    disabled.start();

    assertThat(disabled.isRunning()).isFalse();
    verifyNoInteractions(scheduler);
  }

  private void reads(int... transfersConsumers) {
    for (int count : transfersConsumers) {
      consumerReads.add(OptionalInt.of(count));
    }
  }

  private double releases() {
    return registry.get(EntriesQueueRebalancer.RELEASES_COUNTER).counter().count();
  }

  private static ProjectionConsumerProperties defaultConsumerProperties() {
    return new ProjectionConsumerProperties(
        "rabbitmq", null, null, null, null, null, 0, 0, 0, 0, null, null, null, null);
  }
}

package dev.fondaria.e2e;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.core.ConditionTimeoutException;

/**
 * The wait-for-convergence helper Task 6.6 requires in place of a sleep.
 *
 * <p><b>Why not a sleep.</b> The path a movement travels here is asynchronous at three hops — the
 * CDC job's stream, the bridge's produce-and-confirm, the projection's consume-and-commit — and
 * none of them promises a duration. A sleep either passes by being longer than the slowest hop on
 * the day it was written (and fails later, on a loaded runner, reporting scheduler contention as a
 * defect about money) or is trimmed until it does exactly that. This polls the condition and
 * returns the moment it holds, so a healthy run pays nothing for a generous budget.
 *
 * <p><b>Why the budget is generous.</b> Sixty seconds is not a claim about the platform's latency —
 * Task 4.2 measures that, and asserts it, at two seconds steady-state. It is the point past which
 * "the pipeline is stuck" is a better explanation than "the runner is busy". Nothing here measures
 * anything; the assertions that follow a convergence do.
 */
final class Convergence {

  /**
   * How long a movement may take to travel ledger → CDC → bridge → projection before it is a bug.
   */
  static final Duration BUDGET = Duration.ofSeconds(60);

  private Convergence() {}

  /**
   * Polls until {@code condition} holds, or fails naming {@code what}.
   *
   * <p>Exceptions from the condition are treated as "not yet": a row that does not exist yet is
   * routinely a lookup that throws rather than one that returns nothing. On a timeout the last of
   * them is reported, with the pipeline's queue depths.
   */
  static void awaitConvergence(String what, Callable<Boolean> condition) {
    awaitConvergence(what, BUDGET, condition);
  }

  /**
   * Waits until every surface Task 6.6's chain criterion names carries this scenario's contribution
   * — and waits on the <b>figures</b>, never on a status code or a row count.
   *
   * <p>The pipeline lands on two independent Redpanda topics: {@code ledger.transfers.v1} feeds
   * {@code projection.transfer} (the movement history and the employer's contributions total) and
   * {@code ledger.entries.v1} feeds {@code projection.participant_units} (the as-of series). They
   * are joined by nothing but a shared checkpoint, so their arrival is not ordered against each
   * other, and the allotment posts <b>three</b> transfers — two unit legs and the employer's cash
   * leg — spread across 32 and 64 partitions.
   *
   * <p>Every weaker condition is satisfied too early by something real:
   *
   * <ul>
   *   <li><b>A 200 from balance-as-of</b> arrives with the <i>first</i> of the two holding legs —
   *       the endpoint 404s only when no account has a row at all — while the assertion that
   *       follows it is about the sum of both.
   *   <li><b>Two movements</b> proves the two <i>unit</i> transfers landed and says nothing about
   *       the cash leg the organization summary counts.
   * </ul>
   *
   * <p>Waiting on each figure the tests then assert removes all of it. A run where the pipeline is
   * healthy pays nothing for it.
   */
  static void awaitScenarioIsQueryable(QueryApi query, Scenario scenario) {
    awaitConvergence(
        "both allotment legs reach the participant's movement history",
        () -> query.movements(scenario.participantId()).json().get("items").size() == 2);
    awaitConvergence(
        "both holdings reach the as-of series for the dealing date",
        () ->
            query
                    .balanceAsOf(scenario.participantId(), scenario.dealingDate())
                    .json()
                    .get("valueCents")
                    .asLong()
                == scenario.totalCents());
    awaitConvergence(
        "the employer's cash leg reaches the contributions total",
        () ->
            query.summary(scenario.organizationId()).asLong("contributionsThisPeriodCents")
                == scenario.contributionsInPeriod(query));
  }

  static void awaitConvergence(String what, Duration budget, Callable<Boolean> condition) {
    AtomicReference<Exception> lastFailure = new AtomicReference<>();
    try {
      await()
          .alias(what)
          .atMost(budget)
          .pollInterval(Duration.ofMillis(200))
          .pollDelay(Duration.ZERO)
          .until(
              () -> {
                try {
                  return condition.call();
                } catch (Exception e) {
                  lastFailure.set(e);
                  return false;
                }
              });
    } catch (ConditionTimeoutException e) {
      // "Not fulfilled" alone says nothing about which hop stalled. The last error the condition
      // raised and how much is still queued at each hop usually do.
      AssertionError failure =
          new AssertionError(
              what
                  + " did not converge within "
                  + budget
                  + "; last condition error: "
                  + (lastFailure.get() == null ? "none" : lastFailure.get())
                  + "; pipeline queue depths: "
                  + queueDepthsOrWhyNot(),
              e);
      throw failure;
    }
  }

  private static String queueDepthsOrWhyNot() {
    try {
      return PlatformStack.getInstance().pipelineQueueDepths().toString();
    } catch (RuntimeException e) {
      return "unavailable (" + e + ")";
    }
  }
}

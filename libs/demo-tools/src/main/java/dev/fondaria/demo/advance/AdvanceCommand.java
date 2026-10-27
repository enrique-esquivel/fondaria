package dev.fondaria.demo.advance;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.demo.Args;
import dev.fondaria.demo.DemoCalendar;
import dev.fondaria.demo.DemoPaths;
import dev.fondaria.demo.DemoToolsException;
import dev.fondaria.demo.k8s.Kubectl;
import dev.fondaria.demo.manifest.DemoState;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;

/**
 * {@code demo-tools advance --business-days N} or {@code advance --to month-end} -- moves the
 * platform's simulated "today" forward (`phase-D-demo.md` Task D.1/D.2). The one command in this
 * module that writes Postgres, and it does so through {@code kubectl exec} rather than a JDBC
 * driver: a driver would be this module's first new dependency, where every other command needs
 * none, and the credential CloudNativePG generates is reachable only through the cluster's own
 * tooling in the first place.
 *
 * <p>{@code --to month-end} moves to the last business day of the month the next business day falls
 * in: always forward, and never onto a date already passed. Statements are monthly, and only that
 * date's close fans them out ({@code demo-tools statements}).
 *
 * <p>The write is the operation that matters and fails loud on any error (a decrease the {@code
 * demo_clock_forbid_decrease} trigger refuses, a missing credential, an unreachable cluster).
 * Confirming that all six clock-reading services have actually picked the new offset up is best
 * effort ({@link DemoClock#awaitConvergence}) -- the offset is already written by the time any of
 * that runs.
 */
public final class AdvanceCommand {

  /**
   * Mid-morning on the target date, comfortably clear of the 18:00 cut-off in either direction, so
   * a few seconds of real time elapsing while this command runs never changes which business date
   * the offset resolves to. Well before eod-job's close window too: the day's business comes first.
   */
  static final LocalTime START_OF_DAY = LocalTime.of(9, 0);

  private AdvanceCommand() {}

  public static void run(Args args, DemoPaths paths) {
    String kubeContext = env("KUBE_CONTEXT", "kind-fondaria");
    String namespace = env("K8S_NAMESPACE", "fondaria");

    BusinessCalendar calendar = DemoCalendar.create();
    DemoState state = DemoState.readFrom(paths.state(), calendar.businessDateOf(Instant.now()));
    LocalDate newDate =
        targetDate(
            calendar,
            state.currentBusinessDate(),
            args.getInt("business-days", 1),
            args.get("to", ""));

    ZonedDateTime target = ZonedDateTime.of(newDate, START_OF_DAY, DemoCalendar.ZONE);
    long offsetSeconds = DemoClock.offsetFor(target, Instant.now());
    if (offsetSeconds < 0) {
      throw new DemoToolsException(
          "computed a negative offset ("
              + offsetSeconds
              + "s) advancing to "
              + newDate
              + " -- the demo clock only ever moves forward");
    }

    System.out.println(
        "Advancing: "
            + state.currentBusinessDate()
            + " -> "
            + newDate
            + (DemoCalendar.isLastBusinessDayOfMonth(calendar, newDate)
                ? " (last business day of "
                    + YearMonth.from(newDate)
                    + ": its close fans out"
                    + " the month's statements)"
                : "")
            + " (offset "
            + offsetSeconds
            + "s)");

    Kubectl kubectl = new Kubectl(kubeContext, namespace);
    DemoClock clock = new DemoClock(kubectl, kubectl.readPostgresCredential());
    clock.write(offsetSeconds);

    state.withCurrentBusinessDate(newDate).writeTo(paths.state());
    System.out.println("reference.demo_clock updated. Waiting for services to converge...");

    clock.awaitConvergence(offsetSeconds);
  }

  /**
   * The business date to move to.
   *
   * @param to {@code ""} to move {@code businessDays} forward, or {@code month-end}
   */
  static LocalDate targetDate(
      BusinessCalendar calendar, LocalDate current, int businessDays, String to) {
    if (to.isEmpty()) {
      if (businessDays < 1) {
        throw new DemoToolsException("--business-days must be at least 1, got " + businessDays);
      }
      LocalDate date = current;
      for (int i = 0; i < businessDays; i++) {
        date = calendar.nextBusinessDate(date);
      }
      return date;
    }
    if (to.equals("month-end")) {
      YearMonth month = YearMonth.from(calendar.nextBusinessDate(current));
      return DemoCalendar.lastBusinessDayOf(calendar, month);
    }
    throw new DemoToolsException("unknown --to " + to + " (the one supported value is month-end)");
  }

  private static String env(String variable, String defaultValue) {
    String value = System.getenv(variable);
    return (value == null || value.isBlank()) ? defaultValue : value;
  }
}

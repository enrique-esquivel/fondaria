package dev.fondaria.demo.close;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.demo.Args;
import dev.fondaria.demo.DemoCalendar;
import dev.fondaria.demo.DemoPaths;
import dev.fondaria.demo.DemoToolsException;
import dev.fondaria.demo.advance.DemoClock;
import dev.fondaria.demo.k8s.Kubectl;
import dev.fondaria.demo.k8s.PsqlRows;
import dev.fondaria.demo.manifest.DemoState;
import dev.fondaria.demo.statement.StatementsCommand;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@code demo-tools close} -- ends the current simulated business day and shows its daily close.
 *
 * <p>eod-job's close (internal reconciliation, custodian reconciliation, dense snapshot, sign-off)
 * is a scheduled background process, not something this command runs: the demo overlays give it a
 * close window ({@code CLOSE_WINDOW_START=17:00}) and a short tick. This command moves the
 * simulated clock from the 09:00 {@code advance} left it at to just inside that window -- the same
 * business date, still before cut-off -- and then reads the audit rows the close writes until the
 * date is signed off, printing each step as it lands and then the sign-off with the evidence it
 * cites ({@link CloseReport}) and the manifest hash chain ({@link HashChainCheck}).
 *
 * <p>Re-running it for a date already signed off just prints that sign-off again. On the month's
 * last business day it goes on to wait for and show the month's statements ({@link
 * StatementsCommand}).
 */
public final class CloseCommand {

  /**
   * A few minutes past the window's start, so a second of clock skew never lands just before it.
   */
  private static final Duration INTO_THE_WINDOW = Duration.ofMinutes(5);

  private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

  private CloseCommand() {}

  public static void run(Args args, DemoPaths paths) {
    Kubectl kubectl =
        new Kubectl(env("KUBE_CONTEXT", "kind-fondaria"), env("K8S_NAMESPACE", "fondaria"));
    Kubectl.Credential credential = kubectl.readPostgresCredential();
    BusinessCalendar calendar = DemoCalendar.create();
    LocalDate businessDate =
        DemoState.readFrom(paths.state(), calendar.businessDateOf(Instant.now()))
            .currentBusinessDate();
    LocalTime windowStart =
        LocalTime.parse(args.get("window-start", env("CLOSE_WINDOW_START", "17:00")));
    Duration timeout = Duration.ofSeconds(args.getLong("timeout-seconds", 900));

    System.out.println("Closing business date " + businessDate + ".");
    boolean windowPassed =
        !moveIntoCloseWindow(
            new DemoClock(kubectl, credential), calendar, businessDate, windowStart);

    CloseReport report = awaitSignoff(kubectl, credential, businessDate, timeout, windowPassed);
    HashChainCheck chain =
        HashChainCheck.fromRow(
            PsqlRows.single(kubectl.query(credential, HashChainCheck.QUERY.formatted(businessDate)))
                .orElseThrow());
    System.out.println();
    report.render(env("SNAPSHOT_BUCKET", "eod-snapshots"), chain).forEach(System.out::println);

    if (DemoCalendar.isLastBusinessDayOfMonth(calendar, businessDate)) {
      System.out.println();
      StatementsCommand.show(kubectl, credential, paths, YearMonth.from(businessDate), args);
    } else {
      System.out.println();
      System.out.println(
          "Statements are monthly: "
              + YearMonth.from(businessDate)
              + "'s are fanned out by the close of "
              + DemoCalendar.lastBusinessDayOf(calendar, YearMonth.from(businessDate))
              + ". `make demo-statements` advances there, runs that day and shows them.");
    }
  }

  /**
   * Moves the clock to {@code windowStart + 5 min} on {@code businessDate}, never backwards. A
   * clock already inside the window is left alone; one already past cut-off is reported, since that
   * date's window has passed and whatever close it got is what there is to show.
   *
   * @return whether the date's close window is (or is now) open; {@code false} once it has passed
   */
  private static boolean moveIntoCloseWindow(
      DemoClock clock, BusinessCalendar calendar, LocalDate businessDate, LocalTime windowStart) {
    long current = clock.currentOffsetSeconds();
    Instant simulatedNow = Instant.now().plusSeconds(current);
    if (calendar.businessDateOf(simulatedNow).isAfter(businessDate)) {
      System.out.println(
          "  The simulated clock is already past "
              + businessDate
              + "'s cut-off; showing the close that date got.");
      return false;
    }
    ZonedDateTime target =
        ZonedDateTime.of(businessDate, windowStart, DemoCalendar.ZONE).plus(INTO_THE_WINDOW);
    long offset = DemoClock.offsetFor(target, Instant.now());
    if (offset <= current) {
      System.out.println("  The simulated clock is already inside the close window.");
      return true;
    }
    clock.write(offset);
    System.out.println(
        "  Simulated clock -> "
            + target.toLocalDateTime()
            + " (eod-job's close window opens at "
            + windowStart
            + "). Waiting for eod-job to see it...");
    clock.awaitEodJobConvergence(offset);
    return true;
  }

  private static CloseReport awaitSignoff(
      Kubectl kubectl,
      Kubectl.Credential credential,
      LocalDate businessDate,
      Duration timeout,
      boolean windowPassed) {
    Instant deadline = Instant.now().plus(timeout);
    Optional<CloseProgress> last = Optional.empty();
    while (true) {
      CloseProgress progress =
          CloseProgress.fromRow(
              PsqlRows.single(
                      kubectl.query(credential, CloseProgress.QUERY.formatted(businessDate)))
                  .orElseThrow());
      if (last.isEmpty() || !last.get().equals(progress)) {
        System.out.println("  " + progress.describe());
        last = Optional.of(progress);
      }
      boolean pastDeadline = Instant.now().isAfter(deadline);
      if (progress.signedOff() && (progress.announced() || pastDeadline)) {
        List<List<String>> rows =
            kubectl.query(credential, CloseReport.QUERY.formatted(businessDate));
        return CloseReport.fromRow(PsqlRows.single(rows).orElseThrow());
      }
      if (windowPassed && !progress.signedOff()) {
        throw new DemoToolsException(
            businessDate
                + " was never signed off, and its close window has passed: the clock cannot go"
                + " back to it. Last state: "
                + progress.describe()
                + ".");
      }
      if (pastDeadline) {
        throw new DemoToolsException(
            businessDate
                + " was not signed off within "
                + timeout
                + ". Last state: "
                + progress.describe()
                + ". See Financial Control in Grafana, or `kubectl logs deploy/eod-job`.");
      }
      DemoClock.sleep(POLL_INTERVAL);
    }
  }

  private static String env(String variable, String defaultValue) {
    String value = System.getenv(variable);
    return (value == null || value.isBlank()) ? defaultValue : value;
  }
}

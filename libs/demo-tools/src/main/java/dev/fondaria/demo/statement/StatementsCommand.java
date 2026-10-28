package dev.fondaria.demo.statement;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.demo.Args;
import dev.fondaria.demo.DemoCalendar;
import dev.fondaria.demo.DemoPaths;
import dev.fondaria.demo.DemoToolsException;
import dev.fondaria.demo.Display;
import dev.fondaria.demo.advance.DemoClock;
import dev.fondaria.demo.k8s.Kubectl;
import dev.fondaria.demo.k8s.PsqlRows;
import dev.fondaria.demo.manifest.DemoState;
import dev.fondaria.demo.manifest.HeroParticipant;
import dev.fondaria.demo.manifest.Manifest;
import dev.fondaria.demo.manifest.OrganizationRecord;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code demo-tools statements [--period YYYY-MM] [--organization N]} -- waits for a month's
 * statement run to complete and prints one hero participant's statement, read back from the
 * statement-objects bucket where statement-worker stored it.
 *
 * <p>Statements are monthly. {@code close.signed-off.v1} for the month's last business day is the
 * only trigger ({@code StatementScheduler}); any other day's sign-off fans nothing out. So a period
 * whose month-end close has not been signed off has no run to wait for, and this says so rather
 * than waiting.
 *
 * <p>The statement's object key carries the opaque {@code participantId} and never the internal
 * ref; it holds amounts and no name, so printing it shows the operator nothing a participant's own
 * statement would not.
 */
public final class StatementsCommand {

  private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

  private StatementsCommand() {}

  public static void run(Args args, DemoPaths paths) {
    Kubectl kubectl =
        new Kubectl(env("KUBE_CONTEXT", "kind-fondaria"), env("K8S_NAMESPACE", "fondaria"));
    BusinessCalendar calendar = DemoCalendar.create();
    LocalDate today =
        DemoState.readFrom(paths.state(), calendar.businessDateOf(Instant.now()))
            .currentBusinessDate();
    YearMonth period = YearMonth.parse(args.get("period", YearMonth.from(today).toString()));
    show(kubectl, kubectl.readPostgresCredential(), paths, period, args);
  }

  /** Waits for {@code period}'s run, then prints a hero's statement and where it is stored. */
  public static void show(
      Kubectl kubectl,
      Kubectl.Credential credential,
      DemoPaths paths,
      YearMonth period,
      Args args) {
    BusinessCalendar calendar = DemoCalendar.create();
    LocalDate monthEnd = DemoCalendar.lastBusinessDayOf(calendar, period);
    Duration timeout = Duration.ofSeconds(args.getLong("timeout-seconds", 900));

    StatementRunStatus run = awaitRun(kubectl, credential, period, monthEnd, timeout);
    System.out.println(
        "Statement run "
            + period
            + ": "
            + run.status()
            + " -- "
            + Display.count(Long.parseLong(run.completedCount()))
            + " of "
            + Display.count(run.expectedCount())
            + " statements stored, fanned out by the "
            + run.closeBusinessDate()
            + " close.");

    HeroParticipant hero =
        hero(Manifest.readFrom(paths.manifest()), args.getInt("organization", 0));
    String bucket = env("STATEMENT_BUCKET", "statement-objects");
    String key = objectKey(period, hero.participantId());
    String json;
    try {
      json = kubectl.readObject(bucket + "/" + key, env("OBJECT_STORE_REGION", "us-east-1"));
    } catch (DemoToolsException e) {
      throw new DemoToolsException(
          "could not read the hero participant "
              + hero.participantId()
              + "'s statement at s3://"
              + bucket
              + "/"
              + key
              + " -- one with no open position at month end has none ("
              + e.getMessage()
              + ")");
    }
    StatementWaterfall statement = StatementWaterfall.parse(json);
    System.out.println();
    System.out.println(
        "Hero participant " + statement.participantId() + ", statement for " + statement.period());
    System.out.println("  s3://" + bucket + "/" + key);
    statement.render().forEach(System.out::println);
  }

  /**
   * {@code statements/period=YYYY-MM/participant=<participantId>.json} -- statement-worker's own
   * key ({@code StatementRenderer#objectKey}). The id is parsed as a UUID first, so nothing but a
   * UUID ever reaches the object path.
   */
  static String objectKey(YearMonth period, String participantId) {
    return "statements/period="
        + period
        + "/participant="
        + UUID.fromString(participantId)
        + ".json";
  }

  private static HeroParticipant hero(Manifest manifest, int organizationIndex) {
    if (organizationIndex < 0 || organizationIndex >= manifest.organizations().size()) {
      throw new DemoToolsException(
          "--organization "
              + organizationIndex
              + " is out of range: the manifest has "
              + manifest.organizations().size()
              + " organizations");
    }
    OrganizationRecord organization = manifest.organizations().get(organizationIndex);
    return organization.heroes().stream()
        .filter(h -> h.participantId() != null && !h.participantId().isBlank())
        .findFirst()
        .orElseThrow(
            () ->
                new DemoToolsException(
                    "organization " + organizationIndex + " has no hero with a participantId"));
  }

  private static StatementRunStatus awaitRun(
      Kubectl kubectl,
      Kubectl.Credential credential,
      YearMonth period,
      LocalDate monthEnd,
      Duration timeout) {
    Instant deadline = Instant.now().plus(timeout);
    Optional<String> lastStatus = Optional.empty();
    while (true) {
      Optional<StatementRunStatus> run =
          PsqlRows.single(kubectl.query(credential, StatementRunStatus.QUERY.formatted(period)))
              .map(StatementRunStatus::fromRow);
      if (run.isPresent() && run.get().completed()) {
        return run.get();
      }
      if (run.isPresent() && run.get().failed()) {
        throw new DemoToolsException(
            "the statement run for "
                + period
                + " FAILED -- see `kubectl logs deploy/statement-worker`");
      }
      if (run.isEmpty() && !signedOff(kubectl, credential, monthEnd)) {
        throw new DemoToolsException(
            period
                + "'s statements are fanned out by the close of its last business day, "
                + monthEnd
                + ", which has not been signed off. `make demo-statements` advances there, runs"
                + " that day and closes it.");
      }
      String status =
          run.map(r -> "RUNNING, " + Display.count(r.expectedCount()) + " statements expected")
              .orElse("waiting for statement-worker to pick up the " + monthEnd + " sign-off");
      if (lastStatus.isEmpty() || !lastStatus.get().equals(status)) {
        System.out.println("  Statement run " + period + ": " + status);
        lastStatus = Optional.of(status);
      }
      if (Instant.now().isAfter(deadline)) {
        throw new DemoToolsException(
            "the statement run for " + period + " did not complete within " + timeout);
      }
      DemoClock.sleep(POLL_INTERVAL);
    }
  }

  private static boolean signedOff(
      Kubectl kubectl, Kubectl.Credential credential, LocalDate businessDate) {
    return !kubectl
        .query(
            credential,
            "SELECT 1 FROM workflow.close_signoff WHERE business_date = DATE '"
                + businessDate
                + "'")
        .isEmpty();
  }

  private static String env(String variable, String defaultValue) {
    String value = System.getenv(variable);
    return (value == null || value.isBlank()) ? defaultValue : value;
  }
}

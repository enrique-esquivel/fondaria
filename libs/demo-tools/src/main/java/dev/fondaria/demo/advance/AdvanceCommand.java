package dev.fondaria.demo.advance;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.demo.Args;
import dev.fondaria.demo.DemoCalendar;
import dev.fondaria.demo.DemoEndpoints;
import dev.fondaria.demo.DemoPaths;
import dev.fondaria.demo.DemoToolsException;
import dev.fondaria.demo.http.DemoHttpClient;
import dev.fondaria.demo.json.JsonParser;
import dev.fondaria.demo.k8s.Kubectl;
import dev.fondaria.demo.manifest.DemoState;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * {@code demo-tools advance --business-days N} -- moves the platform's simulated "today" forward
 * (`phase-D-demo.md` Task D.1/D.2). The one command in this module that touches Postgres, and it
 * does so through {@code kubectl exec} rather than a JDBC driver: a driver would be this module's
 * first new dependency, where every other command needs none, and the credential CloudNativePG
 * generates is reachable only through the cluster's own tooling in the first place.
 *
 * <p>The write is the operation that matters and fails loud on any error (a decrease the {@code
 * demo_clock_forbid_decrease} trigger refuses, a missing credential, an unreachable cluster).
 * Confirming that all six clock-reading services have actually picked the new offset up is best
 * effort: three (`contribution-api`, `balance-api`, `valuation-api`) are polled directly over their
 * NodePorts, and three (`allotment-job`, `eod-job`, `statement-worker`) have no public endpoint at
 * all, so this command opens a transient {@code kubectl port-forward} to each in turn. A
 * convergence check that cannot run (no {@code kubectl} on this operator's machine, a firewalled
 * cluster) is reported, never silently skipped and never why this command fails -- the offset is
 * already written by the time any of this runs.
 */
public final class AdvanceCommand {

  private static final ZoneId MEXICO_CITY = ZoneId.of("America/Mexico_City");
  private static final Duration CONVERGENCE_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration CONVERGENCE_POLL_INTERVAL = Duration.ofSeconds(2);

  private record ClusterService(String name, boolean hasNodePort) {}

  private static final List<ClusterService> SERVICES_WITH_DEMO_CLOCK =
      List.of(
          new ClusterService("contribution-api", true),
          new ClusterService("balance-api", true),
          new ClusterService("valuation-api", true),
          new ClusterService("allotment-job", false),
          new ClusterService("eod-job", false),
          new ClusterService("statement-worker", false));

  private AdvanceCommand() {}

  public static void run(Args args, DemoPaths paths) {
    int businessDays = args.getInt("business-days", 1);
    String kubeContext = env("KUBE_CONTEXT", "kind-fondaria");
    String namespace = env("K8S_NAMESPACE", "fondaria");

    BusinessCalendar calendar = DemoCalendar.create();
    DemoState state = DemoState.readFrom(paths.state(), calendar.businessDateOf(Instant.now()));
    LocalDate newDate = state.currentBusinessDate();
    for (int i = 0; i < businessDays; i++) {
      newDate = calendar.nextBusinessDate(newDate);
    }

    // Mid-morning on the target date, comfortably clear of the 18:00 cut-off in either direction,
    // so a few seconds of real time elapsing while this command runs never changes which business
    // date the offset resolves to.
    ZonedDateTime target = ZonedDateTime.of(newDate, LocalTime.of(9, 0), MEXICO_CITY);
    long offsetSeconds = target.toInstant().getEpochSecond() - Instant.now().getEpochSecond();
    if (offsetSeconds < 0) {
      throw new DemoToolsException(
          "computed a negative offset ("
              + offsetSeconds
              + "s) advancing to "
              + newDate
              + " -- the demo clock only ever moves forward");
    }

    System.out.println(
        "Advancing "
            + businessDays
            + " business day(s): "
            + state.currentBusinessDate()
            + " -> "
            + newDate
            + " (offset "
            + offsetSeconds
            + "s)");

    Kubectl kubectl = new Kubectl(kubeContext, namespace);
    Kubectl.Credential credential = kubectl.readPostgresCredential();
    kubectl.execSql(
        credential,
        "UPDATE reference.demo_clock SET offset_seconds = "
            + offsetSeconds
            + ", updated_at = now() WHERE id = 1;");

    state.withCurrentBusinessDate(newDate).writeTo(paths.state());
    System.out.println("reference.demo_clock updated. Waiting for services to converge...");

    awaitConvergence(kubectl, offsetSeconds);
  }

  private static void awaitConvergence(Kubectl kubectl, long targetOffsetSeconds) {
    DemoEndpoints endpoints = DemoEndpoints.fromEnvironment();
    Map<String, String> directUrls =
        Map.of(
            "contribution-api", endpoints.contributionApi(),
            "balance-api", endpoints.balanceApi(),
            "valuation-api", endpoints.valuationApi());

    try (DemoHttpClient http = DemoHttpClient.standard()) {
      for (ClusterService service : SERVICES_WITH_DEMO_CLOCK) {
        try {
          boolean converged;
          if (service.hasNodePort()) {
            converged =
                pollForConvergence(http, directUrls.get(service.name()), targetOffsetSeconds);
          } else {
            int localPort = 18080 + Math.abs(service.name().hashCode() % 1000);
            try (Kubectl.PortForward forward =
                kubectl.portForward(service.name(), localPort, 8080)) {
              converged =
                  pollForConvergence(http, "http://localhost:" + localPort, targetOffsetSeconds);
            }
          }
          System.out.println(
              "  "
                  + service.name()
                  + ": "
                  + (converged ? "converged" : "did not converge within " + CONVERGENCE_TIMEOUT));
        } catch (RuntimeException e) {
          System.out.println(
              "  " + service.name() + ": could not confirm (" + e.getMessage() + ")");
        }
      }
    }
  }

  private static boolean pollForConvergence(
      DemoHttpClient http, String baseUrl, long targetOffsetSeconds) {
    Instant deadline = Instant.now().plus(CONVERGENCE_TIMEOUT);
    URI uri = URI.create(baseUrl + "/actuator/health/demo");
    while (true) {
      try {
        Map<String, Object> body = http.getJson(uri).body();
        long offset = JsonParser.longAt(body, "components.demoClock.details.offsetSeconds");
        if (offset >= targetOffsetSeconds) {
          return true;
        }
      } catch (RuntimeException ignored) {
        // Not ready yet, or briefly unreachable -- keep polling until the deadline.
      }
      if (Instant.now().isAfter(deadline)) {
        return false;
      }
      sleep(CONVERGENCE_POLL_INTERVAL);
    }
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DemoToolsException("interrupted while polling", e);
    }
  }

  private static String env(String variable, String defaultValue) {
    String value = System.getenv(variable);
    return (value == null || value.isBlank()) ? defaultValue : value;
  }
}

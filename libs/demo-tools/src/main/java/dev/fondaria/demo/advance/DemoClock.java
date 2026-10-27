package dev.fondaria.demo.advance;

import dev.fondaria.demo.DemoEndpoints;
import dev.fondaria.demo.DemoToolsException;
import dev.fondaria.demo.http.DemoHttpClient;
import dev.fondaria.demo.json.JsonParser;
import dev.fondaria.demo.k8s.Kubectl;
import dev.fondaria.demo.k8s.PsqlRows;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * The demo's simulated clock as this module sees it: one offset in {@code reference.demo_clock},
 * written through {@code kubectl exec} (see {@link AdvanceCommand} for why not JDBC), and six
 * services that each poll it. {@code advance} moves it to a new business date; {@code close} moves
 * it later within the same date, into eod-job's close window.
 *
 * <p>The offset only ever grows -- {@code demo_clock_forbid_decrease} refuses anything else -- so a
 * caller compares against {@link #currentOffsetSeconds} and leaves a clock already at or past its
 * target alone rather than asking it to go back.
 */
public final class DemoClock {

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

  private final Kubectl kubectl;
  private final Kubectl.Credential credential;

  public DemoClock(Kubectl kubectl, Kubectl.Credential credential) {
    this.kubectl = kubectl;
    this.credential = credential;
  }

  /** The offset that puts the simulated "now" at {@code target} when real time is {@code now}. */
  public static long offsetFor(ZonedDateTime target, Instant now) {
    return target.toInstant().getEpochSecond() - now.getEpochSecond();
  }

  /** The offset currently stored -- what every service converges on within a second or two. */
  public long currentOffsetSeconds() {
    List<List<String>> rows =
        kubectl.query(credential, "SELECT offset_seconds FROM reference.demo_clock WHERE id = 1");
    return PsqlRows.single(rows)
        .map(row -> Long.parseLong(row.get(0).strip()))
        .orElseThrow(() -> new DemoToolsException("reference.demo_clock has no row"));
  }

  /** Writes {@code offsetSeconds}. Fails loud on any error, a refused decrease included. */
  public void write(long offsetSeconds) {
    kubectl.execSql(
        credential,
        "UPDATE reference.demo_clock SET offset_seconds = "
            + offsetSeconds
            + ", updated_at = now() WHERE id = 1;");
  }

  /**
   * Waits, best effort, for every clock-reading service to report the new offset. Three are polled
   * over their NodePorts and three through a transient port-forward, since they have no public
   * endpoint. A check that cannot run is reported, never the reason this fails.
   */
  public void awaitConvergence(long targetOffsetSeconds) {
    awaitConvergence(targetOffsetSeconds, SERVICES_WITH_DEMO_CLOCK);
  }

  /** {@link #awaitConvergence(long)} for eod-job alone -- the one service the close depends on. */
  public void awaitEodJobConvergence(long targetOffsetSeconds) {
    awaitConvergence(
        targetOffsetSeconds,
        SERVICES_WITH_DEMO_CLOCK.stream().filter(s -> s.name().equals("eod-job")).toList());
  }

  private void awaitConvergence(long targetOffsetSeconds, List<ClusterService> services) {
    DemoEndpoints endpoints = DemoEndpoints.fromEnvironment();
    Map<String, String> directUrls =
        Map.of(
            "contribution-api", endpoints.contributionApi(),
            "balance-api", endpoints.balanceApi(),
            "valuation-api", endpoints.valuationApi());

    try (DemoHttpClient http = DemoHttpClient.standard()) {
      for (ClusterService service : services) {
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

  /** Sleeps, turning an interrupt into this module's own failure. */
  public static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DemoToolsException("interrupted while polling", e);
    }
  }
}

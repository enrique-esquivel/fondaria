package dev.fondaria.demo.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.fondaria.demo.Args;
import dev.fondaria.demo.DemoPaths;
import dev.fondaria.demo.contribution.ContributionApiClient;
import dev.fondaria.demo.http.DemoHttpClient;
import dev.fondaria.demo.http.DemoHttpException;
import dev.fondaria.demo.json.Json;
import dev.fondaria.demo.json.JsonParser;
import dev.fondaria.demo.manifest.Manifest;
import dev.fondaria.demo.manifest.OrganizationRecord;
import dev.fondaria.demo.valuation.NavSeriesLog;
import dev.fondaria.demo.valuation.ValuationApiClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs {@link SeedCommand#seed} end to end against a JDK {@link HttpServer} standing in for
 * `contribution-api` and `valuation-api` (as {@code DemoHttpClientTest} does), modelling the one
 * platform behaviour this ordering depends on: a {@code STAGED} batch allots once its fund has a
 * NAV for its dealing date, and not before. Organizations 0, 1 and 3 deal at launch (fund 0); 2 has
 * a one-day dealing lag (`OrganizationPlanRules`).
 */
class SeedCommandRunTest {

  private static final LocalDate LAUNCH = LocalDate.of(2026, 10, 26);

  @TempDir Path outDir;

  private FakePlatform platform;
  private DemoHttpClient http;
  private DemoPaths paths;

  @BeforeEach
  void start() throws IOException {
    platform = new FakePlatform();
    http = new DemoHttpClient(Duration.ofSeconds(2), Duration.ofSeconds(10));
    paths = new DemoPaths(outDir);
  }

  @AfterEach
  void stop() {
    http.close();
    platform.stop();
  }

  private void seed(int organizations, LocalDate valueDate) {
    SeedCommand.seed(
        Args.parse(new String[] {"seed", "--organizations", String.valueOf(organizations)}, 1),
        paths,
        new ContributionApiClient(http, platform.baseUrl()),
        new ValuationApiClient(http, platform.baseUrl()),
        valueDate);
  }

  @Test
  void opensEveryFundBeforeTheFirstOrganizationSoEachBatchAllotsAsItStages() {
    seed(4, LAUNCH);

    int firstNav = platform.events.indexOf("nav");
    int firstOrganization = platform.events.indexOf("organization");
    assertThat(firstNav).isNotNegative().isLessThan(firstOrganization);
    // Zero units in issue, nothing yet to report: every fund opens at its declared price.
    assertThat(platform.acceptedValuations)
        .hasSize(3)
        .allSatisfy((key, cents) -> assertThat(key).endsWith("|" + LAUNCH))
        .containsValues(0L, 0L, 0L);

    Manifest manifest = Manifest.readFrom(paths.manifest());
    // Already allotted by the time each batch settled, not after the last organization staged.
    assertThat(platform.allottedAtSettle)
        .containsExactly(orgId(manifest, 0), orgId(manifest, 1), orgId(manifest, 3));

    String fund0 = manifest.funds().get(0).fundId();
    long dealtAtLaunch = cash(manifest, 0) + cash(manifest, 1) + cash(manifest, 3);
    assertThat(NavSeriesLog.readFrom(paths.navSeries()).lastEntry(fund0))
        .contains(new NavSeriesLog.Entry(LAUNCH.toString(), fund0, null, dealtAtLaunch, 0));
    assertThat(NavSeriesLog.readFrom(paths.navSeries()).lastEntry(manifest.funds().get(1).fundId()))
        .isEmpty();
    // The lagged organization's cash is a later flow, never part of the opening baseline.
    assertThat(Files.exists(paths.fundFlows())).isTrue();
    assertThat(readFlows())
        .containsExactly(Map.entry(fund0 + "|" + LAUNCH.plusDays(1), cash(manifest, 2)));
  }

  @Test
  void aFailedOrganizationLeavesEarlierOnesAllottedAndAResumeCountsThemInTheBaseline() {
    platform.failOrganizationRegistration = 2;

    assertThatThrownBy(() -> seed(4, LAUNCH)).isInstanceOf(DemoHttpException.class);

    Manifest partial = Manifest.readFrom(paths.manifest());
    assertThat(partial.organizations()).hasSize(1);
    assertThat(platform.allotted).containsExactly(orgId(partial, 0));
    assertThat(Files.exists(paths.navSeries())).isFalse();

    seed(4, LAUNCH);

    Manifest manifest = Manifest.readFrom(paths.manifest());
    assertThat(manifest.organizations()).hasSize(4);
    assertThat(manifest.organizations().get(0)).isEqualTo(partial.organizations().get(0));
    assertThat(platform.rejectedValuations).isZero();
    String fund0 = manifest.funds().get(0).fundId();
    long dealtAtLaunch = cash(manifest, 0) + cash(manifest, 1) + cash(manifest, 3);
    NavSeriesLog log = NavSeriesLog.readFrom(paths.navSeries());
    assertThat(log.lastReportedValueCents(fund0)).contains(dealtAtLaunch);
    assertThat(readFlows())
        .containsExactly(Map.entry(fund0 + "|" + LAUNCH.plusDays(1), cash(manifest, 2)));
  }

  @Test
  void resumingWithMoreOrganizationsSupersedesTheBaselineAndARepeatAddsNothing() {
    seed(2, LAUNCH);
    Manifest firstTwo = Manifest.readFrom(paths.manifest());
    String fund0 = firstTwo.funds().get(0).fundId();
    assertThat(NavSeriesLog.readFrom(paths.navSeries()).lastReportedValueCents(fund0))
        .contains(cash(firstTwo, 0) + cash(firstTwo, 1));

    seed(4, LAUNCH);
    seed(4, LAUNCH);

    Manifest manifest = Manifest.readFrom(paths.manifest());
    long dealtAtLaunch = cash(manifest, 0) + cash(manifest, 1) + cash(manifest, 3);
    String navSeries = readString(paths.navSeries());
    assertThat(NavSeriesLog.readFrom(paths.navSeries()).lastReportedValueCents(fund0))
        .contains(dealtAtLaunch);
    assertThat(occurrences(navSeries, "\"fundId\"")).isEqualTo(2);
    assertThat(platform.rejectedValuations).isZero();
    assertThat(platform.organizationsRegistered.get()).isEqualTo(4);
  }

  @Test
  void anOrganizationAddedAfterLaunchDayIsAFlowNotPartOfTheOpeningBaseline() {
    seed(2, LAUNCH);
    LocalDate nextDay = LAUNCH.plusDays(1);

    seed(4, nextDay);

    Manifest manifest = Manifest.readFrom(paths.manifest());
    String fund0 = manifest.funds().get(0).fundId();
    assertThat(manifest.funds())
        .allSatisfy(fund -> assertThat(fund.launchDate()).isEqualTo(LAUNCH));
    assertThat(NavSeriesLog.readFrom(paths.navSeries()).lastReportedValueCents(fund0))
        .contains(cash(manifest, 0) + cash(manifest, 1));
    assertThat(readFlows())
        .containsOnly(
            Map.entry(fund0 + "|" + nextDay.plusDays(1), cash(manifest, 2)),
            Map.entry(fund0 + "|" + nextDay, cash(manifest, 3)));
    assertThat(platform.acceptedValuations).doesNotContainKey(fund0 + "|" + nextDay);
  }

  private static String orgId(Manifest manifest, int index) {
    return manifest.organizations().get(index).organizationId();
  }

  private static long cash(Manifest manifest, int index) {
    OrganizationRecord organization = manifest.organizations().get(index);
    return organization.seedBatch().orElseThrow().declaredTotalCents();
  }

  private Map<String, Object> readFlows() {
    Map<String, Object> flows =
        new LinkedHashMap<>(JsonParser.parseObject(readString(paths.fundFlows())));
    flows.replaceAll((key, value) -> ((Number) value).longValue());
    return flows;
  }

  private static String readString(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private static int occurrences(String text, String needle) {
    return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
  }

  /** Just enough of both APIs for {@code seed}, keeping state across runs like the real estate. */
  private static final class FakePlatform {

    final List<String> events = new ArrayList<>();
    final List<String> allotted = new ArrayList<>();
    final List<String> allottedAtSettle = new ArrayList<>();
    final Map<String, Long> acceptedValuations = new LinkedHashMap<>();
    final AtomicInteger organizationsRegistered = new AtomicInteger();
    int rejectedValuations;

    /** 1-based registration call to fail once with a 500; 0 never fails. */
    int failOrganizationRegistration;

    private final HttpServer server;
    private final Map<String, byte[]> objects = new HashMap<>();
    private final Map<String, String> fundByOrganization = new HashMap<>();
    private final Map<String, Integer> lagByOrganization = new HashMap<>();
    private final Map<String, Map<String, Object>> contributionBatches = new HashMap<>();
    private final Map<String, String> valuationStatus = new HashMap<>();
    private int funds;
    private int organizationCalls;
    private int sequence;

    FakePlatform() throws IOException {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext("/", this::handle);
      server.start();
    }

    String baseUrl() {
      return "http://localhost:" + server.getAddress().getPort();
    }

    void stop() {
      server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      String path = exchange.getRequestURI().getPath();
      byte[] requestBody = exchange.getRequestBody().readAllBytes();
      String[] segments = path.substring(1).split("/");
      Object response;
      if ("PUT".equals(method)) {
        objects.put(path.substring("/objects/".length()), requestBody);
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
        return;
      }
      Map<String, Object> body =
          requestBody.length == 0
              ? Map.of()
              : JsonParser.parseObject(new String(requestBody, StandardCharsets.UTF_8));
      if (path.equals("/v1/funds")) {
        funds++;
        String fundId = body.containsKey("fundId") ? (String) body.get("fundId") : "fund-" + funds;
        events.add("fund");
        response = Map.of("fundId", fundId, "unitLedgerId", 1000 + funds);
      } else if (path.equals("/v1/organizations")) {
        organizationCalls++;
        if (organizationCalls == failOrganizationRegistration) {
          send(exchange, 500, Map.of("title", "boom"));
          return;
        }
        events.add("organization");
        response = Map.of("organizationId", "org-" + organizationsRegistered.incrementAndGet());
      } else if (path.endsWith("/plans")) {
        fundByOrganization.put(segments[2], (String) body.get("fundId"));
        lagByOrganization.put(
            segments[2], ((Number) body.get("dealingLagBusinessDays")).intValue());
        response = Map.of();
      } else if (path.endsWith("/cash-receipts")) {
        response = Map.of();
      } else if (path.endsWith("/upload-url")) {
        String key = "key-" + (++sequence);
        response = Map.of("objectKey", key, "uploadUrl", baseUrl() + "/objects/" + key);
      } else if (path.endsWith("/contribution-batches") && "POST".equals(method)) {
        String organizationId = segments[2];
        String batchId = "batch-" + (++sequence);
        LocalDate dealingDate =
            LocalDate.parse((String) body.get("valueDate"))
                .plusDays(lagByOrganization.get(organizationId));
        contributionBatches.put(
            batchId,
            Map.of("organizationId", organizationId, "dealingDate", dealingDate.toString()));
        response = Map.of("batchId", batchId, "status", "RECEIVED");
      } else if (segments.length == 5 && "contribution-batches".equals(segments[3])) {
        response = contributionBatch(segments[4]);
      } else if (path.endsWith("/participants")) {
        String internalRef = exchange.getRequestURI().getQuery().substring("internalRef=".length());
        response =
            Map.of(
                "internalRef", internalRef,
                "payrollRef", "PAY-" + internalRef,
                "participantId", "participant-" + internalRef);
      } else if (path.equals("/v1/valuation-batches")) {
        String batchId = "valuation-" + (++sequence);
        valuationStatus.put(batchId, acceptValuation(objects.get((String) body.get("objectKey"))));
        response = Map.of("batchId", batchId, "status", "RECEIVED");
      } else if (path.startsWith("/v1/valuation-batches/")) {
        response = Map.of("status", valuationStatus.get(segments[2]));
      } else {
        send(exchange, 404, Map.of("title", "no route " + method + " " + path));
        return;
      }
      send(exchange, 200, response);
    }

    /** STAGED until its fund has a NAV for its dealing date; the allotment sweep then allots it. */
    private Map<String, Object> contributionBatch(String batchId) {
      Map<String, Object> batch = contributionBatches.get(batchId);
      String organizationId = (String) batch.get("organizationId");
      String key = fundByOrganization.get(organizationId) + "|" + batch.get("dealingDate");
      boolean struck = acceptedValuations.containsKey(key);
      if (struck && !allotted.contains(organizationId)) {
        allotted.add(organizationId);
        allottedAtSettle.add(organizationId);
      }
      Map<String, Object> response = new LinkedHashMap<>(batch);
      response.put("status", struck ? "ALLOTTED" : "STAGED");
      return response;
    }

    /** Check 9: an identical row is a no-op, a different value for a live date a rejection. */
    private String acceptValuation(byte[] csv) {
      String[] lines = new String(csv, StandardCharsets.UTF_8).split("\n");
      Map<String, Long> rows = new LinkedHashMap<>();
      for (int i = 1; i < lines.length; i++) {
        String[] columns = lines[i].split(",");
        String key = columns[0] + "|" + columns[1];
        long cents = Long.parseLong(columns[2]);
        Optional<Long> live = Optional.ofNullable(acceptedValuations.get(key));
        if (live.isPresent() && live.get() != cents) {
          rejectedValuations++;
          return "REJECTED";
        }
        rows.put(key, cents);
      }
      rows.forEach(
          (key, cents) -> {
            if (acceptedValuations.putIfAbsent(key, cents) == null) {
              events.add("nav");
            }
          });
      return "ACCEPTED";
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
      byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    }
  }
}

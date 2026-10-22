package dev.fondaria.demo.onboard;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.demo.Args;
import dev.fondaria.demo.DemoCalendar;
import dev.fondaria.demo.DemoEndpoints;
import dev.fondaria.demo.DemoPaths;
import dev.fondaria.demo.DemoToolsException;
import dev.fondaria.demo.auth.DemoAuth;
import dev.fondaria.demo.contribution.ContributionApiClient;
import dev.fondaria.demo.contribution.ContributionBatchSubmitter;
import dev.fondaria.demo.contribution.ContributionFileGenerator;
import dev.fondaria.demo.http.DemoHttpClient;
import dev.fondaria.demo.manifest.DemoState;
import dev.fondaria.demo.manifest.FundFlows;
import dev.fondaria.demo.manifest.FundRecord;
import dev.fondaria.demo.manifest.Manifest;
import dev.fondaria.demo.seed.FreshRfc;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * {@code demo-tools onboard --employees 200000} -- the demo's climax: one employer, one file, one
 * wire, submitted live against an already-running cluster (`phase-D-demo.md` Task D.2).
 *
 * <p>Registers into a fund the manifest already carries rather than a fresh one, so this
 * organization's batch deals against a price the ordinary {@code seed}/{@code contributions}/{@code
 * valuations} cycle has already struck for today -- onboarding a new employer is not itself a
 * reason to strike a NAV. If nothing has struck one yet (this is the very first thing run against a
 * fresh cluster), the batch stays {@code STAGED} and this command's own timeout says so, naming
 * {@code demo-tools valuations} as the fix, rather than silently doing that step's job here.
 */
public final class OnboardCommand {

  /**
   * A reserved organization index -- clear of {@code seed}'s 0..11 and any {@code contributions}
   * organization index -- so this run's participant refs (`P-999-0`, ...) never collide with an
   * existing organization's.
   */
  private static final int ORGANIZATION_INDEX = 999;

  private OnboardCommand() {}

  public static void run(Args args, DemoPaths paths) {
    if (!java.nio.file.Files.exists(paths.manifest())) {
      throw new DemoToolsException("no " + paths.manifest() + " -- run `demo-tools seed` first");
    }
    long seed = args.getLong("seed", 42L);
    int employeeCount = args.getInt("employees", 200_000);
    Manifest manifest = Manifest.readFrom(paths.manifest());
    FundRecord fund = manifest.fundByVehicleType("CAJA_DE_AHORRO");

    BusinessCalendar calendar = DemoCalendar.create();
    // The platform's simulated business date, not real wall-clock time: every other command reads
    // this same cursor, and this organization must deal on the date the demo clock is actually at,
    // not whatever the operator's own clock says (`phase-D-demo.md` Task D.1).
    DemoState state = DemoState.readFrom(paths.state(), calendar.businessDateOf(Instant.now()));
    LocalDate valueDate = state.currentBusinessDate();
    String planId = "PLAN-ONBOARD-" + System.currentTimeMillis();
    String legalName = "Demo Empleador Live " + employeeCount;

    DemoEndpoints endpoints = DemoEndpoints.fromEnvironment();
    try (DemoHttpClient http = DemoHttpClient.authenticated(new DemoAuth()::bearerToken)) {
      ContributionApiClient client = new ContributionApiClient(http, endpoints.contributionApi());

      Instant registerStart = Instant.now();
      Map<String, Object> organization =
          client.registerOrganization(
              FreshRfc.forOrganization(seed, ORGANIZATION_INDEX), legalName);
      String organizationId = (String) organization.get("organizationId");
      client.publishPlanVersion(organizationId, planId, valueDate, 1, 1, 0, 0, fund.fundId());
      System.out.println(
          "Registered " + legalName + " (" + organizationId + ") in " + elapsed(registerStart));

      Instant generateStart = Instant.now();
      ContributionFileGenerator.GeneratedFile file =
          ContributionFileGenerator.generate(
              seed, ORGANIZATION_INDEX, employeeCount, valueDate, 1, 1, 0);
      System.out.println("Generated " + file.rowCount() + " rows in " + elapsed(generateStart));

      client.recordCashReceipt(
          organizationId,
          "onboard-" + planId,
          file.declaredTotalCents(),
          valueDate,
          "ONBOARD-WIRE",
          "demo-tools");

      Instant submitStart = Instant.now();
      Map<String, Object> submitted =
          ContributionBatchSubmitter.uploadAndSubmit(
              client,
              organizationId,
              planId,
              "ONBOARD",
              valueDate,
              "onboard-" + employeeCount + ".csv",
              file.csv(),
              file.declaredTotalCents(),
              file.rowCount(),
              file.declaredSourceCents());
      String batchId = (String) submitted.get("batchId");
      System.out.println(
          "Uploaded and submitted in " + elapsed(submitStart) + " (batch " + batchId + ")");

      Instant allotmentStart = Instant.now();
      PollOutcome outcome =
          pollAndReportProgress(
              client, organizationId, batchId, Duration.ofMinutes(30), Duration.ofSeconds(5));
      Duration allotmentDuration = Duration.between(allotmentStart, Instant.now());
      Map<String, Object> settled = outcome.batch();

      if ("REJECTED".equals(settled.get("status"))) {
        throw new DemoToolsException("onboarding batch was rejected: " + settled.get("rejection"));
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> parts = (Map<String, Object>) settled.get("parts");
      long allottedParts = parts == null ? 0 : ((Number) parts.get("allottedParts")).longValue();
      long partCount = parts == null ? 1 : ((Number) parts.get("partCount")).longValue();
      boolean allotted =
          parts != null ? allottedParts >= partCount : "ALLOTTED".equals(settled.get("status"));

      if (outcome.timedOut() || !allotted) {
        throw new DemoToolsException(
            "onboarding did not finish after "
                + allotmentDuration
                + " (last status "
                + settled.get("status")
                + ", "
                + allottedParts
                + "/"
                + partCount
                + " parts allotted)."
                + " If nothing progressed, run `demo-tools valuations` first -- this fund may have no struck price for "
                + valueDate
                + ".");
      }

      // Onboarding's cash grows the fund's pool after whatever valuation last struck it, exactly
      // like an ordinary contribution -- the next valuation must see it as "today's net
      // contributions", keyed by the date this batch actually dealt at.
      LocalDate dealingDate =
          settled.get("dealingDate") == null
              ? valueDate
              : LocalDate.parse((String) settled.get("dealingDate"));
      FundFlows flows = FundFlows.readFrom(paths.fundFlows());
      flows.add(fund.fundId(), dealingDate, file.declaredTotalCents());
      flows.writeTo(paths.fundFlows());

      System.out.println(
          "Onboarded "
              + employeeCount
              + " employees across "
              + partCount
              + " part(s) in "
              + allotmentDuration);
    }
  }

  private record PollOutcome(Map<String, Object> batch, boolean timedOut) {}

  @SuppressWarnings("unchecked")
  private static PollOutcome pollAndReportProgress(
      ContributionApiClient client,
      String organizationId,
      String batchId,
      Duration timeout,
      Duration pollInterval) {
    Instant deadline = Instant.now().plus(timeout);
    while (true) {
      Map<String, Object> batch = client.getContributionBatch(organizationId, batchId).body();
      String status = (String) batch.get("status");
      Map<String, Object> parts = (Map<String, Object>) batch.get("parts");
      if ("REJECTED".equals(status)) {
        return new PollOutcome(batch, false);
      }
      if (parts != null) {
        long partCount = ((Number) parts.get("partCount")).longValue();
        long allottedParts = ((Number) parts.get("allottedParts")).longValue();
        System.out.printf("  parts allotted: %d/%d%n", allottedParts, partCount);
        if (allottedParts >= partCount) {
          return new PollOutcome(batch, false);
        }
      } else if ("ALLOTTED".equals(status)) {
        return new PollOutcome(batch, false);
      }
      if (Instant.now().isAfter(deadline)) {
        return new PollOutcome(batch, true);
      }
      sleep(pollInterval);
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

  private static String elapsed(Instant start) {
    return Duration.between(start, Instant.now()).toString();
  }
}

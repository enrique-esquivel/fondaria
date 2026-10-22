package dev.fondaria.demo.contribution;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.demo.Args;
import dev.fondaria.demo.DemoCalendar;
import dev.fondaria.demo.DemoEndpoints;
import dev.fondaria.demo.DemoPaths;
import dev.fondaria.demo.DemoToolsException;
import dev.fondaria.demo.auth.DemoAuth;
import dev.fondaria.demo.http.DemoHttpClient;
import dev.fondaria.demo.manifest.DemoState;
import dev.fondaria.demo.manifest.FundFlows;
import dev.fondaria.demo.manifest.Manifest;
import dev.fondaria.demo.manifest.OrganizationRecord;
import dev.fondaria.demo.seed.OrganizationPlanRules;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * {@code demo-tools contributions [--period P] [--date D] [--corrupt]} -- one file per organization
 * for a payroll period, or (with {@code --corrupt}) one deliberately wrong file for the first
 * organization, proving the validation gate rejects it (`phase-D-demo.md` Task D.2).
 */
public final class ContributionsCommand {

  private ContributionsCommand() {}

  public static void run(Args args, DemoPaths paths) {
    if (!java.nio.file.Files.exists(paths.manifest())) {
      throw new DemoToolsException("no " + paths.manifest() + " -- run `demo-tools seed` first");
    }
    long seed = args.getLong("seed", 42L);
    Manifest manifest = Manifest.readFrom(paths.manifest());
    BusinessCalendar calendar = DemoCalendar.create();
    DemoState state = DemoState.readFrom(paths.state(), calendar.businessDateOf(Instant.now()));
    LocalDate valueDate =
        args.has("date") ? LocalDate.parse(args.require("date")) : state.currentBusinessDate();

    DemoEndpoints endpoints = DemoEndpoints.fromEnvironment();
    try (DemoHttpClient http = DemoHttpClient.authenticated(new DemoAuth()::bearerToken)) {
      ContributionApiClient client = new ContributionApiClient(http, endpoints.contributionApi());

      if (args.has("corrupt")) {
        runCorrupt(client, seed, manifest, valueDate);
        return;
      }

      String period = args.get("period", periodLabel(state.periodIndex()));
      FundFlows flows = FundFlows.readFrom(paths.fundFlows());

      System.out.println(
          "Submitting contributions for period " + period + " (value date " + valueDate + ")...");
      for (int i = 0; i < manifest.organizations().size(); i++) {
        OrganizationRecord organization = manifest.organizations().get(i);
        int[] ratio = OrganizationPlanRules.matchRatio(i);
        ContributionFileGenerator.GeneratedFile file =
            ContributionFileGenerator.generate(
                seed, i, organization.employeeCount(), valueDate, ratio[0], ratio[1], 0);

        client.recordCashReceipt(
            organization.organizationId(),
            "contrib-" + period + "-" + i,
            file.declaredTotalCents(),
            valueDate,
            "PAYROLL-" + period + "-" + i,
            "demo-tools");

        Map<String, Object> submitted =
            ContributionBatchSubmitter.uploadAndSubmit(
                client,
                organization.organizationId(),
                organization.planId(),
                period,
                valueDate,
                "demo-" + period + "-" + i + ".csv",
                file.csv(),
                file.declaredTotalCents(),
                file.rowCount(),
                file.declaredSourceCents());

        Map<String, Object> settled =
            ContributionBatchSubmitter.pollUntilSettled(
                client,
                organization.organizationId(),
                (String) submitted.get("batchId"),
                Duration.ofMinutes(3),
                Duration.ofSeconds(2));
        if ("REJECTED".equals(settled.get("status"))) {
          throw new DemoToolsException(
              "organization "
                  + i
                  + "'s contribution batch was rejected: "
                  + settled.get("rejection"));
        }

        // Keyed by the batch's own dealingDate, not the date it was submitted on: a batch with a
        // non-zero dealing lag turns into units on a later date, and the fund's pool only actually
        // grows on the date the units appear. dealingDate is present because status is STAGED or
        // ALLOTTED here (pollUntilSettled only returns on REJECTED/STAGED/ALLOTTED); an already
        // ALLOTTED batch (allotment-job ran within the poll window) carries no dealingDate any
        // more, so valueDate is the correct fallback -- that is necessarily the date it dealt at.
        LocalDate dealingDate =
            settled.get("dealingDate") == null
                ? valueDate
                : LocalDate.parse((String) settled.get("dealingDate"));
        flows.add(organization.fundId(), dealingDate, file.declaredTotalCents());
        System.out.printf(
            "  %s: %d participants, %d cents declared, status %s%n",
            organization.legalName(),
            organization.employeeCount(),
            file.declaredTotalCents(),
            settled.get("status"));
      }

      flows.writeTo(paths.fundFlows());
      state.withPeriodIndex(state.periodIndex() + 1).writeTo(paths.state());
      System.out.println(
          "Contributions submitted for " + manifest.organizations().size() + " organization(s).");
    }
  }

  /**
   * Submits one intentionally wrong file (grand total off by one cent) and confirms the platform
   * rejects it.
   */
  private static void runCorrupt(
      ContributionApiClient client, long seed, Manifest manifest, LocalDate valueDate) {
    OrganizationRecord organization = manifest.organizations().get(0);
    int[] ratio = OrganizationPlanRules.matchRatio(0);
    ContributionFileGenerator.GeneratedFile file =
        ContributionFileGenerator.generate(
            seed, 0, organization.employeeCount(), valueDate, ratio[0], ratio[1], 0);
    long corruptedTotal = ContributionFileGenerator.corruptedDeclaredTotalCents(file);
    String period = "CORRUPT-" + System.currentTimeMillis();

    System.out.println(
        "Submitting a deliberately corrupted file for " + organization.legalName() + "...");
    Map<String, Object> submitted =
        ContributionBatchSubmitter.uploadAndSubmit(
            client,
            organization.organizationId(),
            organization.planId(),
            period,
            valueDate,
            "corrupt.csv",
            file.csv(),
            corruptedTotal,
            file.rowCount(),
            file.declaredSourceCents());

    Map<String, Object> settled =
        ContributionBatchSubmitter.pollUntilSettled(
            client,
            organization.organizationId(),
            (String) submitted.get("batchId"),
            Duration.ofMinutes(2),
            Duration.ofSeconds(2));

    if (!"REJECTED".equals(settled.get("status"))) {
      throw new DemoToolsException("expected the corrupted file to be REJECTED, was: " + settled);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> rejection = (Map<String, Object>) settled.get("rejection");
    String code = rejection == null ? null : (String) rejection.get("code");
    if (!"DECLARED_TOTAL_MISMATCH".equals(code)) {
      throw new DemoToolsException("expected DECLARED_TOTAL_MISMATCH, got: " + code);
    }
    System.out.println(
        "Corrupted file correctly rejected with code " + code + " -- the validation gate is on.");
  }

  private static final LocalDate PERIOD_BASE = LocalDate.of(2026, 10, 1);

  private static String periodLabel(int periodIndex) {
    LocalDate month = PERIOD_BASE.plusMonths(periodIndex / 2L);
    String half = periodIndex % 2 == 0 ? "A" : "B";
    return String.format("%04d-%02d-%s", month.getYear(), month.getMonthValue(), half);
  }
}

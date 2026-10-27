package dev.fondaria.demo.seed;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.Nav;
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
import dev.fondaria.demo.manifest.HeroParticipant;
import dev.fondaria.demo.manifest.Manifest;
import dev.fondaria.demo.manifest.OrganizationRecord;
import dev.fondaria.demo.valuation.NavSeriesLog;
import dev.fondaria.demo.valuation.ValuationApiClient;
import dev.fondaria.demo.valuation.ValuationBatchSubmitter;
import dev.fondaria.demo.valuation.ValuationFileGenerator;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code demo-tools seed} -- 3 funds, 12 employers of realistic sizes and one contribution each, so
 * every one of ~300,000 participants exists, and every one dealing today holds units after the
 * first allotment (`phase-D-demo.md` Task D.2).
 *
 * <p>Two phases. Phase A registers every organization, its plan and its first contribution file,
 * and waits only for whole-batch validation to settle (not for allotment, since a batch dealing on
 * a future date has nothing to allot against yet). Phase B then strikes, in one valuation file per
 * fund, the very first NAV each fund dealing <em>today</em> needs, and waits for those
 * organizations (most of the twelve) to actually allot. Organizations dealt at a later date are
 * left {@code STAGED} on purpose -- {@code awaitingAllotment} is a first-class state the later demo
 * beats show settling, not a bug this command should mask.
 *
 * <p>Resumable rather than re-run-from-scratch: {@code demo/out/manifest.json} already carrying N
 * organizations means the first N are done, and this command continues from N -- reusing the exact
 * fund ids already assigned, since registering a fund with an omitted {@code fundId} mints a new
 * one every time.
 */
public final class SeedCommand {

  /**
   * MXN 10.00 per unit -- a round opening price, so a rounding surprise here would be a real one.
   */
  private static final long TEN_PESOS_PER_UNIT = 10 * Nav.SCALE;

  private static final int HEROES_PER_ORGANIZATION = 3;

  private record SettledBatch(
      int organizationIndex,
      String organizationId,
      String batchId,
      String fundId,
      LocalDate dealingDate,
      long declaredTotalCents) {}

  private SeedCommand() {}

  static int organizationCount(Args args, int max) {
    int count = args.getInt("organizations", max);
    if (count < 1 || count > max) {
      throw new DemoToolsException(
          "--organizations must be between 1 and " + max + ", got " + count);
    }
    return count;
  }

  /**
   * How long to wait for one organization's seed batch to stage. Validation, enrolment and staging
   * grow with the row count: the 31,115-row batch took over four minutes on the small GKE profile's
   * three 4-vCPU nodes, past a flat three-minute wait, and a timed-out seed cannot simply be re-run
   * because the organization is already in the manifest. Three minutes plus 10 ms a row.
   */
  static Duration settleTimeout(int rows) {
    return Duration.ofMinutes(3).plusMillis(10L * rows);
  }

  public static void run(Args args, DemoPaths paths) {
    long seed = args.getLong("seed", 42L);
    DemoEndpoints endpoints = DemoEndpoints.fromEnvironment();
    BusinessCalendar calendar = DemoCalendar.create();
    LocalDate valueDate = calendar.businessDateOf(Instant.now());
    int[] allSizes = OrganizationSizes.generate();
    // --organizations N seeds only the first N (smallest first); a later run with a larger N, or
    // none, resumes from the manifest and adds the rest -- new employers joining a live estate.
    int[] sizes = java.util.Arrays.copyOf(allSizes, organizationCount(args, allSizes.length));

    Manifest manifest =
        Files.exists(paths.manifest()) ? Manifest.readFrom(paths.manifest()) : Manifest.empty();

    // Establishes the cursor every other command reads, at the business date seed actually ran on
    // -- never overwritten if it already exists, since a resumed seed run must not clobber a cursor
    // `contributions`/`valuations`/`advance` may already have moved forward in the meantime.
    if (!Files.exists(paths.state())) {
      DemoState.initial(valueDate).writeTo(paths.state());
    }

    try (DemoHttpClient http = DemoHttpClient.authenticated(new DemoAuth()::bearerToken)) {
      ContributionApiClient contributionApi =
          new ContributionApiClient(http, endpoints.contributionApi());
      ValuationApiClient valuationApi = new ValuationApiClient(http, endpoints.valuationApi());

      if (manifest.funds().isEmpty()) {
        System.out.println("Registering 3 funds...");
        manifest
            .funds()
            .add(
                registerFund(
                    contributionApi, null, "Caja de Ahorro Uno", "CAJA_DE_AHORRO", valueDate));
        manifest
            .funds()
            .add(
                registerFund(
                    contributionApi, null, "Caja de Ahorro Dos", "CAJA_DE_AHORRO", valueDate));
        manifest
            .funds()
            .add(
                registerFund(
                    contributionApi,
                    null,
                    "Fondo de Retiro Patronal",
                    "RETIREMENT_FUND",
                    valueDate));
        manifest.writeTo(paths.manifest());
      } else {
        System.out.println(
            "Reusing "
                + manifest.funds().size()
                + " already-registered funds from "
                + paths.manifest());
        for (FundRecord fund : manifest.funds()) {
          // Replays the ORIGINAL launchDate, never today's -- registering the same (fundId,
          // version) with a different effectiveFrom is a different body under the same key, which
          // the platform refuses with 409 rather than silently accepting.
          registerFund(
              contributionApi, fund.fundId(), fund.name(), fund.vehicleType(), fund.launchDate());
        }
      }

      int alreadyDone = manifest.organizations().size();
      if (alreadyDone >= sizes.length) {
        System.out.println("All " + sizes.length + " organizations already seeded; nothing to do.");
        return;
      }

      List<SettledBatch> stagedAwaitingToday = new ArrayList<>();
      FundFlows flows = FundFlows.readFrom(paths.fundFlows());
      long totalParticipants = 0;
      for (OrganizationRecord existing : manifest.organizations()) {
        totalParticipants += existing.employeeCount();
      }

      for (int i = alreadyDone; i < sizes.length; i++) {
        int employeeCount = sizes[i];
        FundRecord fund = manifest.funds().get(OrganizationPlanRules.fundIndex(i));
        int dealingLag = OrganizationPlanRules.dealingLagBusinessDays(i);
        System.out.printf(
            "Organization %d/%d: %d employees, fund %s, dealing lag %d business day(s)...%n",
            i + 1, sizes.length, employeeCount, fund.name(), dealingLag);

        OrganizationAndBatch seeded =
            seedOrganization(contributionApi, seed, i, employeeCount, fund, valueDate);
        manifest.organizations().add(seeded.organization());
        manifest.writeTo(paths.manifest());
        totalParticipants += employeeCount;

        if ("REJECTED".equals(seeded.status())) {
          throw new DemoToolsException(
              "organization " + i + "'s seed batch was rejected: " + seeded.rejection());
        }
        if ("ALLOTTED".equals(seeded.status())) {
          // Already fully allotted -- only possible on a resumed run against a fund whose NAV a
          // previous attempt already struck. Nothing left to wait for or to record: that NAV's
          // baseline already reflects this organization's cash.
          continue;
        }
        if (!"STAGED".equals(seeded.status())) {
          throw new DemoToolsException(
              "organization "
                  + i
                  + "'s seed batch settled at an unexpected status: "
                  + seeded.status());
        }
        SettledBatch settled =
            new SettledBatch(
                i,
                seeded.organization().organizationId(),
                seeded.batchId(),
                fund.fundId(),
                seeded.dealingDate(),
                seeded.declaredTotalCents());
        if (valueDate.equals(seeded.dealingDate())) {
          // Dealt today: this cash becomes part of the fund's very first NAV strike below, so it
          // belongs in that strike's baseline, not in the flows a later valuation would drain --
          // recording it in both places would double-count it on the next day's move.
          stagedAwaitingToday.add(settled);
        } else {
          // Dealt on a later date (a non-zero dealing lag): the cash isn't in the pool yet, so it
          // is exactly "net contributions" for whichever future valuation actually strikes that
          // date -- keyed by dealingDate, not by today, so it surfaces on the right day.
          flows.add(fund.fundId(), seeded.dealingDate(), seeded.declaredTotalCents());
        }
      }
      flows.writeTo(paths.fundFlows());

      strikeInitialValuationsAndAwaitAllotment(
          valuationApi, contributionApi, stagedAwaitingToday, valueDate, paths);

      System.out.println("Wrote " + paths.manifest());
      System.out.println("Total participants seeded: " + totalParticipants);
    }
  }

  private record OrganizationAndBatch(
      OrganizationRecord organization,
      String batchId,
      String status,
      LocalDate dealingDate,
      long declaredTotalCents,
      Object rejection) {}

  private static FundRecord registerFund(
      ContributionApiClient client,
      String fundId,
      String name,
      String vehicleType,
      LocalDate launchDate) {
    Map<String, Object> response =
        client.registerFund(fundId, name, vehicleType, launchDate, TEN_PESOS_PER_UNIT);
    return new FundRecord(
        (String) response.get("fundId"),
        ((Number) response.get("unitLedgerId")).longValue(),
        vehicleType,
        name,
        launchDate);
  }

  private static OrganizationAndBatch seedOrganization(
      ContributionApiClient client,
      long seed,
      int organizationIndex,
      int employeeCount,
      FundRecord fund,
      LocalDate valueDate) {
    boolean isRetirementFund = OrganizationPlanRules.isRetirementFund(fund.vehicleType());
    int[] ratio = OrganizationPlanRules.matchRatio(organizationIndex);
    int dealingLag = OrganizationPlanRules.dealingLagBusinessDays(organizationIndex);
    String planId = "PLAN-DEMO-" + organizationIndex;
    String legalName = "Demo Empleador " + (organizationIndex + 1) + " S.A. de C.V.";

    Map<String, Object> organization =
        client.registerOrganization(FreshRfc.forOrganization(seed, organizationIndex), legalName);
    String organizationId = (String) organization.get("organizationId");

    client.publishPlanVersion(
        organizationId,
        planId,
        valueDate,
        ratio[0],
        ratio[1],
        isRetirementFund ? 5 : 0,
        dealingLag,
        fund.fundId());

    ContributionFileGenerator.GeneratedFile file =
        ContributionFileGenerator.generate(
            seed,
            organizationIndex,
            employeeCount,
            valueDate,
            ratio[0],
            ratio[1],
            HEROES_PER_ORGANIZATION);

    String payrollPeriod =
        valueDate.getYear() + "-" + String.format("%02d", valueDate.getMonthValue()) + "-SEED";
    client.recordCashReceipt(
        organizationId,
        "seed-" + seed + "-" + organizationIndex,
        file.declaredTotalCents(),
        valueDate,
        "SEED-" + organizationIndex,
        "demo-tools");

    Map<String, Object> submitted =
        ContributionBatchSubmitter.uploadAndSubmit(
            client,
            organizationId,
            planId,
            payrollPeriod,
            valueDate,
            "demo-seed-" + organizationIndex + ".csv",
            file.csv(),
            file.declaredTotalCents(),
            file.rowCount(),
            file.declaredSourceCents());
    String batchId = (String) submitted.get("batchId");

    Map<String, Object> settled =
        ContributionBatchSubmitter.pollUntilSettled(
            client, organizationId, batchId, settleTimeout(employeeCount), Duration.ofSeconds(2));
    String status = (String) settled.get("status");
    LocalDate dealingDate =
        settled.get("dealingDate") == null
            ? null
            : LocalDate.parse((String) settled.get("dealingDate"));

    List<HeroParticipant> heroes = new ArrayList<>();
    if (!"REJECTED".equals(status)) {
      for (String internalRef : file.heroInternalRefs()) {
        Map<String, Object> lookup =
            client.lookupParticipantByInternalRef(organizationId, internalRef);
        heroes.add(
            new HeroParticipant(
                (String) lookup.get("internalRef"),
                (String) lookup.get("payrollRef"),
                (String) lookup.get("participantId")));
      }
    }

    OrganizationRecord record =
        new OrganizationRecord(
            organizationId, legalName, planId, fund.fundId(), employeeCount, heroes);
    return new OrganizationAndBatch(
        record, batchId, status, dealingDate, file.declaredTotalCents(), settled.get("rejection"));
  }

  /**
   * The very first NAV each fund needs, struck once per fund in one valuation file, then waits for
   * every organization dealing today at that fund to actually allot.
   *
   * <p>The reported value for this very first strike is the actual cash being allotted into the
   * fund today, summed across every organization dealing at it -- not an arbitrary placeholder. The
   * strike itself ignores it (zero units in issue means {@code NavStrikeService} falls back to the
   * fund's opening price), but this same number becomes {@code nav-series.json}'s baseline
   * ("previous reported value") for tomorrow's move, and that computation is not forgiving: a
   * placeholder there would make the very next valuation strike a NAV off by several orders of
   * magnitude from the fund's real pool size.
   */
  private static void strikeInitialValuationsAndAwaitAllotment(
      ValuationApiClient valuationApi,
      ContributionApiClient contributionApi,
      List<SettledBatch> stagedAwaitingToday,
      LocalDate valueDate,
      DemoPaths paths) {
    if (stagedAwaitingToday.isEmpty()) {
      return;
    }
    Map<String, Long> reportedValueByFund = new LinkedHashMap<>();
    for (SettledBatch batch : stagedAwaitingToday) {
      reportedValueByFund.merge(batch.fundId(), batch.declaredTotalCents(), Long::sum);
    }

    System.out.println(
        "Striking the opening NAV for "
            + reportedValueByFund.size()
            + " fund(s) at "
            + valueDate
            + "...");
    ValuationFileGenerator.GeneratedFile valuationFile =
        ValuationFileGenerator.generateInitial(valueDate, reportedValueByFund, "DEMO-SEED");
    Map<String, Object> valuationBatch =
        ValuationBatchSubmitter.uploadAndSubmit(
            valuationApi,
            "DEMO-SEED",
            "seed-opening-nav.csv",
            valuationFile.csv(),
            valuationFile.rowCount());
    Map<String, Object> settledValuation =
        ValuationBatchSubmitter.pollUntilTerminal(
            valuationApi,
            (String) valuationBatch.get("batchId"),
            Duration.ofMinutes(2),
            Duration.ofSeconds(2));
    if (!"ACCEPTED".equals(settledValuation.get("status"))) {
      throw new DemoToolsException(
          "seed's opening valuation was rejected: " + settledValuation.get("rejection"));
    }

    NavSeriesLog navSeriesLog = NavSeriesLog.readFrom(paths.navSeries());
    reportedValueByFund.forEach(
        (fundId, value) ->
            navSeriesLog.append(
                new NavSeriesLog.Entry(valueDate.toString(), fundId, null, value, 0)));
    navSeriesLog.writeTo(paths.navSeries());

    System.out.println(
        "Waiting for " + stagedAwaitingToday.size() + " organization(s) to allot...");
    for (SettledBatch batch : stagedAwaitingToday) {
      ContributionBatchSubmitter.pollUntilAllotted(
          contributionApi,
          batch.organizationId(),
          batch.batchId(),
          Duration.ofMinutes(5),
          Duration.ofSeconds(3));
    }
  }
}

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
import dev.fondaria.demo.manifest.SeedBatch;
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
import java.util.Optional;
import java.util.TreeMap;

/**
 * {@code demo-tools seed} -- 3 funds, 12 employers of realistic sizes and one contribution each, so
 * every one of ~300,000 participants exists, and every one dealing today holds units after the
 * first allotment (`phase-D-demo.md` Task D.2).
 *
 * <p>The opening NAV comes first. Right after the funds are registered, one valuation file strikes
 * each fund's opening price at its launch date, with zero units in issue (so {@code
 * NavStrikeService} falls back to the fund's declared opening price whatever is reported). Every
 * organization's batch dealing that day then allots as soon as it stages -- the allotment sweep
 * picks up any {@code STAGED} batch whose dealing date is struck -- so the estate fills in
 * organization by organization, and a failure in the last one leaves every earlier one allotted
 * rather than nothing at all. Organizations dealt at a later date are left {@code STAGED} on
 * purpose -- {@code awaitingAllotment} is a first-class state the later demo beats show settling,
 * not a bug this command should mask.
 *
 * <p>Resumable rather than re-run-from-scratch: {@code demo/out/manifest.json} already carrying N
 * organizations means the first N are done, and this command continues from N -- reusing the exact
 * fund ids already assigned, since registering a fund with an omitted {@code fundId} mints a new
 * one every time. A resumed run re-sends the identical opening valuation (an idempotent no-op once
 * accepted) and counts every organization in the manifest, not just its own, in the baseline it
 * records.
 */
public final class SeedCommand {

  /**
   * MXN 10.00 per unit -- a round opening price, so a rounding surprise here would be a real one.
   */
  private static final long TEN_PESOS_PER_UNIT = 10 * Nav.SCALE;

  private static final int HEROES_PER_ORGANIZATION = 3;

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
    DemoEndpoints endpoints = DemoEndpoints.fromEnvironment();
    BusinessCalendar calendar = DemoCalendar.create();
    LocalDate valueDate = calendar.businessDateOf(Instant.now());
    try (DemoHttpClient http = DemoHttpClient.authenticated(new DemoAuth()::bearerToken)) {
      seed(
          args,
          paths,
          new ContributionApiClient(http, endpoints.contributionApi()),
          new ValuationApiClient(http, endpoints.valuationApi()),
          valueDate);
    }
  }

  static void seed(
      Args args,
      DemoPaths paths,
      ContributionApiClient contributionApi,
      ValuationApiClient valuationApi,
      LocalDate valueDate) {
    long seed = args.getLong("seed", 42L);
    int[] allSizes = OrganizationSizes.generate();
    // --organizations N seeds only the first N (smallest first); a later run with a larger N, or
    // none, resumes from the manifest and adds the rest -- new employers joining a live estate.
    int[] sizes = java.util.Arrays.copyOf(allSizes, organizationCount(args, allSizes.length));

    Manifest manifest =
        Files.exists(paths.manifest()) ? Manifest.readFrom(paths.manifest()) : Manifest.empty();
    requireSeedBatchesRecorded(manifest);

    // Establishes the cursor every other command reads, at the business date seed actually ran on
    // -- never overwritten if it already exists, since a resumed seed run must not clobber a cursor
    // `contributions`/`valuations`/`advance` may already have moved forward in the meantime.
    if (!Files.exists(paths.state())) {
      DemoState.initial(valueDate).writeTo(paths.state());
    }

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
                  contributionApi, null, "Fondo de Retiro Patronal", "RETIREMENT_FUND", valueDate));
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

    strikeOpeningNavs(valuationApi, manifest.funds());

    int alreadyDone = manifest.organizations().size();
    if (alreadyDone >= sizes.length) {
      System.out.println("All " + sizes.length + " organizations already seeded.");
    }

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
      if (!"STAGED".equals(seeded.status()) && !"ALLOTTED".equals(seeded.status())) {
        throw new DemoToolsException(
            "organization "
                + i
                + "'s seed batch settled at an unexpected status: "
                + seeded.status());
      }
      SeedBatch batch = seeded.organization().seedBatch().orElseThrow();
      if (!batch.dealsOn(fund.launchDate())) {
        // Dealt on a later date (a non-zero dealing lag, or a run resumed after launch day): the
        // cash isn't in the pool yet, so it is exactly "net contributions" for whichever future
        // valuation actually strikes that date -- keyed by dealingDate, not by today, so it
        // surfaces on the right day. Cash dealt at the opening NAV goes into the baseline below
        // instead; recording it in both places would double-count it on the next day's move.
        // Written per organization, alongside the manifest entry that makes a resumed run skip it.
        LocalDate dealingDate =
            batch
                .dealingDate()
                .orElseThrow(
                    () ->
                        new DemoToolsException(
                            "organization "
                                + seeded.organization().organizationId()
                                + "'s seed batch settled "
                                + seeded.status()
                                + " with no dealing date to record its cash against"));
        flows.add(fund.fundId(), dealingDate, batch.declaredTotalCents());
        flows.writeTo(paths.fundFlows());
      }
    }

    Map<String, Long> baseline = openingBaseline(manifest);
    NavSeriesLog navSeriesLog = NavSeriesLog.readFrom(paths.navSeries());
    recordOpeningBaseline(navSeriesLog, manifest.funds(), baseline);
    navSeriesLog.writeTo(paths.navSeries());

    awaitOpeningAllotment(contributionApi, manifest);

    System.out.println("Wrote " + paths.manifest());
    System.out.println("Total participants seeded: " + totalParticipants);
  }

  /**
   * A manifest written before {@code seed} recorded each organization's batch cannot say how much
   * cash its organizations dealt at the opening NAV, so a resumed run could not record a correct
   * baseline from it. Refused up front, before anything is sent, rather than guessed.
   */
  static void requireSeedBatchesRecorded(Manifest manifest) {
    for (OrganizationRecord organization : manifest.organizations()) {
      if (organization.seedBatch().isEmpty()) {
        throw new DemoToolsException(
            "manifest organization "
                + organization.organizationId()
                + " has no seedBatch -- it was written by an older seed that did not record one;"
                + " start from an empty demo/out against a fresh estate");
      }
    }
  }

  /**
   * Each fund's opening valuation, grouped by launch date: a reported value of zero, since nothing
   * has been allotted yet and units in issue are zero -- the strike takes the fund's declared
   * opening price. Identical on every run for the same funds, so a resumed run re-sending it is a
   * no-op the platform accepts rather than a contradicting valuation it refuses.
   */
  static Map<LocalDate, Map<String, Long>> openingValuations(List<FundRecord> funds) {
    Map<LocalDate, Map<String, Long>> byDate = new TreeMap<>();
    for (FundRecord fund : funds) {
      byDate
          .computeIfAbsent(fund.launchDate(), date -> new LinkedHashMap<>())
          .put(fund.fundId(), 0L);
    }
    return byDate;
  }

  /**
   * Cash dealt at each fund's opening NAV, summed over every organization in the manifest -- those
   * this run seeded and those an earlier run did. Only funds some organization dealt into on launch
   * day appear, as before: a fund nobody invested in on that day has no baseline to move from.
   *
   * <p>This is {@code nav-series.json}'s baseline ("previous reported value") for the next day's
   * move, and that computation is not forgiving: anything other than the real pool size here would
   * make the very next valuation strike a NAV off by the same proportion.
   */
  static Map<String, Long> openingBaseline(Manifest manifest) {
    Map<String, Long> baseline = new LinkedHashMap<>();
    for (FundRecord fund : manifest.funds()) {
      long cents = 0L;
      for (OrganizationRecord organization : manifest.organizations()) {
        SeedBatch batch = organization.seedBatch().orElseThrow();
        if (organization.fundId().equals(fund.fundId()) && batch.dealsOn(fund.launchDate())) {
          cents = Math.addExact(cents, batch.declaredTotalCents());
        }
      }
      if (cents != 0L) {
        baseline.put(fund.fundId(), cents);
      }
    }
    return baseline;
  }

  /**
   * Appends each fund's opening baseline at its launch date, unless the log already ends on that
   * same value -- so a resumed run that added launch-day cash supersedes the earlier baseline, and
   * one that added none leaves the log as it was. A fund whose log already moved past launch day
   * has been valued since, and its baseline is no longer this command's to restate.
   */
  static void recordOpeningBaseline(
      NavSeriesLog log, List<FundRecord> funds, Map<String, Long> baseline) {
    for (FundRecord fund : funds) {
      Long cents = baseline.get(fund.fundId());
      if (cents == null) {
        continue;
      }
      String launchDate = fund.launchDate().toString();
      Optional<NavSeriesLog.Entry> last = log.lastEntry(fund.fundId());
      boolean record =
          last.map(entry -> entry.date().equals(launchDate) && entry.reportedValueCents() != cents)
              .orElse(true);
      if (record) {
        log.append(new NavSeriesLog.Entry(launchDate, fund.fundId(), null, cents, 0));
      }
    }
  }

  private record OrganizationAndBatch(
      OrganizationRecord organization, String status, Object rejection) {}

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
    Optional<LocalDate> dealingDate =
        "REJECTED".equals(status)
            ? Optional.empty()
            : Optional.ofNullable((String) settled.get("dealingDate"))
                .map(LocalDate::parse)
                .or(() -> allottedDealingDate(status, valueDate, fund));

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
            organizationId,
            legalName,
            planId,
            fund.fundId(),
            employeeCount,
            heroes,
            Optional.of(new SeedBatch(batchId, dealingDate, file.declaredTotalCents())));
    return new OrganizationAndBatch(record, status, settled.get("rejection"));
  }

  /**
   * The dealing date of a batch that had already allotted by the time it was first seen settled --
   * the opening NAV is struck before any organization, so a small batch dealing that day can pass
   * through {@code STAGED} between two polls. The platform reports {@code dealingDate} only while a
   * batch is awaiting allotment, so it has to be inferred here: a dealing date is never earlier
   * than the value date (`DealingDateRule`), and on launch day the only NAV struck on or after it
   * is the fund's opening one. On any later day it cannot be told apart from a later strike, so it
   * stays empty and the caller refuses it rather than guessing where its cash belongs.
   */
  static Optional<LocalDate> allottedDealingDate(
      String status, LocalDate valueDate, FundRecord fund) {
    return "ALLOTTED".equals(status) && valueDate.equals(fund.launchDate())
        ? Optional.of(fund.launchDate())
        : Optional.empty();
  }

  private static void strikeOpeningNavs(ValuationApiClient valuationApi, List<FundRecord> funds) {
    for (Map.Entry<LocalDate, Map<String, Long>> opening : openingValuations(funds).entrySet()) {
      System.out.println(
          "Striking the opening NAV for "
              + opening.getValue().size()
              + " fund(s) at "
              + opening.getKey()
              + "...");
      ValuationFileGenerator.GeneratedFile valuationFile =
          ValuationFileGenerator.generateInitial(opening.getKey(), opening.getValue(), "DEMO-SEED");
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
    }
  }

  /**
   * Waits for every organization in the manifest that dealt at its fund's opening NAV to finish
   * allotting -- most of them already have by now, since each allots as soon as it stages.
   */
  private static void awaitOpeningAllotment(
      ContributionApiClient contributionApi, Manifest manifest) {
    Map<String, LocalDate> launchDateByFund = new LinkedHashMap<>();
    for (FundRecord fund : manifest.funds()) {
      launchDateByFund.put(fund.fundId(), fund.launchDate());
    }
    List<OrganizationRecord> dealtAtOpening = new ArrayList<>();
    for (OrganizationRecord organization : manifest.organizations()) {
      if (organization
          .seedBatch()
          .orElseThrow()
          .dealsOn(launchDateByFund.get(organization.fundId()))) {
        dealtAtOpening.add(organization);
      }
    }
    System.out.println("Waiting for " + dealtAtOpening.size() + " organization(s) to allot...");
    for (OrganizationRecord organization : dealtAtOpening) {
      ContributionBatchSubmitter.pollUntilAllotted(
          contributionApi,
          organization.organizationId(),
          organization.seedBatch().orElseThrow().batchId(),
          Duration.ofMinutes(5),
          Duration.ofSeconds(3));
    }
  }
}

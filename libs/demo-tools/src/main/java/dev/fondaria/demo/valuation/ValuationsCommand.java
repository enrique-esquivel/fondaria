package dev.fondaria.demo.valuation;

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
import dev.fondaria.demo.manifest.FundRecord;
import dev.fondaria.demo.manifest.Manifest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code demo-tools valuations [--date D] [--move M]} -- one valuation file, one row per fund
 * (`phase-D-demo.md` Task D.2). Without {@code --move}, cycles through {@link
 * NavSeries#SCRIPTED_MOVES}, which is what makes {@code demo-day} rehearsable: the same fall on the
 * same cycle position every run.
 */
public final class ValuationsCommand {

  private ValuationsCommand() {}

  public static void run(Args args, DemoPaths paths) {
    if (!java.nio.file.Files.exists(paths.manifest())) {
      throw new DemoToolsException("no " + paths.manifest() + " -- run `demo-tools seed` first");
    }
    Manifest manifest = Manifest.readFrom(paths.manifest());
    BusinessCalendar calendar = DemoCalendar.create();
    DemoState state = DemoState.readFrom(paths.state(), calendar.businessDateOf(Instant.now()));
    LocalDate valuationDate =
        args.has("date") ? LocalDate.parse(args.require("date")) : state.currentBusinessDate();

    boolean manualMove = args.has("move");
    long moveScaled =
        manualMove
            ? MovePercent.parse(args.require("move"))
            : NavSeries.moveAt(state.navSeriesIndex());

    NavSeriesLog log = NavSeriesLog.readFrom(paths.navSeries());
    FundFlows flows = FundFlows.readFrom(paths.fundFlows());

    List<ValuationFileGenerator.FundValuationInput> inputs = new ArrayList<>();
    for (FundRecord fund : fundsToValue(manifest.funds(), log)) {
      long previous = log.lastReportedValueCents(fund.fundId()).orElseThrow();
      long allotted = flows.drainDealtBefore(fund.fundId(), valuationDate);
      inputs.add(new ValuationFileGenerator.FundValuationInput(fund.fundId(), previous, allotted));
    }

    System.out.printf(
        "Valuing %d fund(s) at %s, move %s...%n", inputs.size(), valuationDate, moveScaled);
    ValuationFileGenerator.GeneratedFile file =
        ValuationFileGenerator.generate(valuationDate, moveScaled, inputs, "DEMO");

    DemoEndpoints endpoints = DemoEndpoints.fromEnvironment();
    try (DemoHttpClient http = DemoHttpClient.authenticated(new DemoAuth()::bearerToken)) {
      ValuationApiClient client = new ValuationApiClient(http, endpoints.valuationApi());
      Map<String, Object> submitted =
          ValuationBatchSubmitter.uploadAndSubmit(
              client, "DEMO", "valuations-" + valuationDate + ".csv", file.csv(), file.rowCount());
      Map<String, Object> settled =
          ValuationBatchSubmitter.pollUntilTerminal(
              client,
              (String) submitted.get("batchId"),
              Duration.ofMinutes(2),
              Duration.ofSeconds(2));
      if (!"ACCEPTED".equals(settled.get("status"))) {
        throw new DemoToolsException("valuation batch was rejected: " + settled.get("rejection"));
      }
    }

    int seriesIndexRecorded = manualMove ? -1 : state.navSeriesIndex();
    file.reportedValueCentsByFund()
        .forEach(
            (fundId, reportedValueCents) ->
                log.append(
                    new NavSeriesLog.Entry(
                        valuationDate.toString(),
                        fundId,
                        moveScaled,
                        reportedValueCents,
                        seriesIndexRecorded)));
    log.writeTo(paths.navSeries());
    flows.writeTo(paths.fundFlows());
    if (!manualMove) {
      state.withNavSeriesIndex(state.navSeriesIndex() + 1).writeTo(paths.state());
    }

    System.out.println("Struck NAVs for " + valuationDate + ": " + file.reportedValueCentsByFund());
  }

  /**
   * The funds this run can value: those {@code seed} has valued before. A fund no seeded employer
   * invests in (the retirement fund, under {@code make demo-seed ORGS=7}) never had a first
   * valuation and holds no units, so there is nothing to price -- it is skipped by name rather than
   * failing the whole run (row 26p: with it, {@code valuations} could not run on such an estate at
   * all). It joins once {@code seed} adds an employer to it. No fund at all is still an error.
   */
  static List<FundRecord> fundsToValue(List<FundRecord> funds, NavSeriesLog log) {
    List<FundRecord> valued = new ArrayList<>();
    for (FundRecord fund : funds) {
      if (log.lastReportedValueCents(fund.fundId()).isPresent()) {
        valued.add(fund);
      } else {
        System.out.printf(
            "Skipping %s (%s): never valued -- no seeded employer invests in it yet%n",
            fund.name(), fund.fundId());
      }
    }
    if (valued.isEmpty()) {
      throw new DemoToolsException("no fund has a prior valuation -- run `demo-tools seed` first");
    }
    return List.copyOf(valued);
  }
}

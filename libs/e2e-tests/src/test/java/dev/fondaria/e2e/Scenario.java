package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.allotmentjob.allotment.AllotmentRunner;
import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Nav;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import dev.fondaria.common.TaxIdScheme;
import dev.fondaria.common.TaxIdentifier;
import dev.fondaria.common.Units;
import dev.fondaria.contribution.batch.BatchDeclaration;
import dev.fondaria.contribution.batch.BatchValidationRunner;
import dev.fondaria.contribution.batch.ContributionBatch;
import dev.fondaria.contribution.batch.ContributionBatchService;
import dev.fondaria.contribution.batch.ContributionBatchStatus;
import dev.fondaria.contribution.batch.ContributionFileParser;
import dev.fondaria.contribution.batch.ContributionSource;
import dev.fondaria.contribution.cashreceipt.CashReceiptService;
import dev.fondaria.contribution.cashreceipt.RecordCashReceiptCommand;
import dev.fondaria.contribution.objectstore.PresignedUpload;
import dev.fondaria.contribution.organization.OrganizationService;
import dev.fondaria.contribution.organization.RegisterOrganizationCommand;
import dev.fondaria.rules.DealingDateRule;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundRepository;
import dev.fondaria.rules.FundStatus;
import dev.fondaria.rules.PlanVersion;
import dev.fondaria.rules.PlanVersionRepository;
import dev.fondaria.valuation.InMemoryNavCache;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One employer's contribution, driven through the platform from a file to a ledger movement.
 *
 * <p>Everything here is a call the platform's own services expose — an upload slot, a cash receipt,
 * the validation sweep, the allotment sweep. Nothing writes a workflow row on their behalf, because
 * the point of this module is that the path is real. The one direct database write is a fund's
 * {@code reference.fund_nav} price, published by {@code valuation-api} in production and out of
 * this chain's scope (see {@link PlatformStack}).
 *
 * <p><b>Every scenario is a fresh organization and participant, and by default a fresh fund.</b>
 * Fixtures are reused across runs and TigerBeetle cannot be reset (docs/ENGINEERING-RULES.md §4),
 * so no assertion in this module may be a total over a table, a topic or a ledger — only over ids
 * the scenario itself created. A series of scenarios may share one {@link TargetFund}, in which
 * case that fund's pooled accounts total <i>those</i> scenarios and nothing else, because the fund
 * is still one nothing before this run had ever registered.
 *
 * @param navScaled the price the units were bought at, 1e-8 scaled
 * @param dealingDate the date the batch dealt at, computed by {@link DealingDateRule} from the
 *     batch's own {@code stagedAt} exactly as {@code allotment-job} computes it — never assumed to
 *     be the value date, which it is only when the batch stages before the day's cut-off
 */
record Scenario(
    OrganizationId organizationId,
    FundId fundId,
    long unitLedgerId,
    String participantInternalRef,
    ParticipantId participantId,
    LocalDate valueDate,
    LocalDate dealingDate,
    long navScaled,
    long employeeCents,
    long employerCents) {

  private static final String PLAN_ID = "PLAN-E2E-CAJA-1";
  private static final String PAYROLL_PERIOD = "2026-08-B";
  private static final String PARTICIPANT_REF = "P-001";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** MXN 10.00 per unit — a round price, so a rounding surprise here would be a real one. */
  static final long TEN_PESOS_PER_UNIT = 10 * Units.SCALE;

  long totalCents() {
    return employeeCents + employerCents;
  }

  /** Units bought by one leg at this scenario's price — the inverse of {@link Money#value}. */
  static long unitsFor(long cents, long navScaled) {
    return java.math.BigInteger.valueOf(cents)
        .multiply(java.math.BigInteger.valueOf(100_000_000_000_000L))
        .divide(java.math.BigInteger.valueOf(navScaled))
        .longValueExact();
  }

  long vestedUnitsScaled() {
    return unitsFor(employeeCents, navScaled);
  }

  /** Employer money under a five-year schedule is issued unvested, and is priced all the same. */
  long unvestedUnitsScaled() {
    return unitsFor(employerCents, navScaled);
  }

  /**
   * How a scenario's dealing-date price comes to exist, so a caller can choose between a {@code
   * reference.fund_nav} row written directly and {@code valuation-api}'s real chain (Task 20.7).
   *
   * <p>The dealing date is not known until the batch has staged — it is derived from the batch's
   * own {@code stagedAt} — so this cannot be hoisted out of {@link #contribute} and done by the
   * caller beforehand.
   */
  @FunctionalInterface
  interface NavStriker {

    /** Prices {@code fundId} for {@code dealingDate}, before the allotment run looks for it. */
    void strike(PlatformStack stack, FundId fundId, LocalDate dealingDate);
  }

  /**
   * A fund a scenario contributes into, and the unit ledger it lives on.
   *
   * <p>Named rather than always freshly registered because Task 21.5's NAV-series criterion is
   * about <b>one</b> fund priced on several dates: participants who buy into different funds cannot
   * receive "correctly different unit counts" from a shared price series, because they would not
   * share one.
   *
   * @param unitLedgerId must fit a TigerBeetle ledger field, which is a u32 — the adapter narrows
   *     it with {@code Math.toIntExact}, so a wider value fails the allotment chain with "integer
   *     overflow" and the batch simply stays {@code STAGED}
   */
  record TargetFund(FundId fundId, long unitLedgerId) {

    /** A fund nobody has registered before, on a unit ledger nobody has used. */
    static TargetFund fresh(PlatformStack stack) {
      long unitLedgerId = ThreadLocalRandom.current().nextLong(1_001L, 2_000_000_000L);
      return new TargetFund(registerFund(stack, unitLedgerId), unitLedgerId);
    }
  }

  /**
   * Runs the whole employer-facing path once: register, receive the wire, upload and submit the
   * file, validate and stage it, strike the price the batch deals at, allot it.
   *
   * @param navScaled the price to strike for the batch's dealing date
   */
  static Scenario contribute(
      PlatformStack stack, long employeeCents, long employerCents, long navScaled) {
    return contribute(stack, TargetFund.fresh(stack), 0, employeeCents, employerCents, navScaled);
  }

  /**
   * The same path, into a named fund at a chosen dealing lag, with the dealing-date price written
   * straight to {@code reference.fund_nav} (Task 21.5).
   *
   * <p>Written rather than delivered as a provider file because a dealing lag puts the dealing date
   * in the future, and a fund cannot be valued on a date that has not happened — {@code
   * valuation-api} is right to refuse it, so a series spanning future dealing dates can only be
   * seeded behind the ingestion path. {@link NavPublicationEndToEndTest} owns the real chain.
   */
  static Scenario contribute(
      PlatformStack stack,
      TargetFund fund,
      int dealingLagBusinessDays,
      long employeeCents,
      long employerCents,
      long navScaled) {
    return contribute(
        stack,
        fund,
        dealingLagBusinessDays,
        employeeCents,
        employerCents,
        navScaled,
        (aStack, fundId, dealingDate) ->
            strikeNav(
                aStack.contributionApiBean(JdbcClient.class), fundId, dealingDate, navScaled));
  }

  /**
   * The same path, with the dealing-date price struck by {@code striker} instead of written
   * directly (Task 20.7).
   *
   * @param navScaled the price {@code striker} is expected to produce. It is what this scenario's
   *     unit arithmetic is computed from, so a striker that strikes something else makes every
   *     figure derived from the returned scenario wrong — which is why {@link
   *     NavPublicationEndToEndTest} asserts the struck price against it rather than assuming it.
   */
  static Scenario contribute(
      PlatformStack stack,
      long employeeCents,
      long employerCents,
      long navScaled,
      NavStriker striker) {
    return contribute(
        stack, TargetFund.fresh(stack), 0, employeeCents, employerCents, navScaled, striker);
  }

  /**
   * The same path again, into a <b>named</b> fund and at a <b>chosen dealing lag</b> (Task 21.5).
   *
   * <p>The lag is the only lever this module has over which date a batch deals at. {@link
   * DealingDateRule} floors a dealing date at the business date the batch staged on, so no value
   * date in the past can move it — a batch is never back-dated to a price already struck (Task
   * 21.1). Two participants therefore deal on two different dates only if their plan versions carry
   * different lags, which is why this parameter exists rather than a value date.
   *
   * @param fund the fund to contribute into — {@link TargetFund#fresh} for a scenario that needs
   *     nobody else's units in the pool, a shared instance for a series of them that do
   * @param dealingLagBusinessDays business days between the batch's value date and the valuation
   *     date it deals at, published on the plan version this scenario's organization gets
   */
  static Scenario contribute(
      PlatformStack stack,
      TargetFund fund,
      int dealingLagBusinessDays,
      long employeeCents,
      long employerCents,
      long navScaled,
      NavStriker striker) {
    PlanVersionRepository planVersions = stack.contributionApiBean(PlanVersionRepository.class);
    CashReceiptService cashReceipts = stack.contributionApiBean(CashReceiptService.class);
    ContributionBatchService batches = stack.contributionApiBean(ContributionBatchService.class);
    BatchValidationRunner validation = stack.contributionApiBean(BatchValidationRunner.class);
    BusinessCalendar calendar = stack.allotmentJobBean(BusinessCalendar.class);
    JdbcClient jdbc = stack.contributionApiBean(JdbcClient.class);

    OrganizationId organizationId = registerOrganization(stack);

    long unitLedgerId = fund.unitLedgerId();
    FundId fundId = fund.fundId();
    planVersions.publish(
        new PlanVersion(
            organizationId,
            PLAN_ID,
            LocalDate.of(2020, 1, 1),
            1,
            1,
            2,
            // Five-year vesting, so the employer's units are issued unvested and the query has
            // both lots to report. Immediate vesting would leave the unvested side untested.
            5,
            dealingLagBusinessDays,
            fundId,
            Instant.parse("2020-01-01T00:00:00Z")));

    // Today's business date: the money arrived today, and the period the organization summary
    // reports on is the calendar month this falls in.
    LocalDate valueDate = calendar.businessDateOf(Instant.now());
    long totalCents = employeeCents + employerCents;

    cashReceipts.record(
        new RecordCashReceiptCommand(
            organizationId,
            "e2e-" + UUID.randomUUID(),
            Money.of(totalCents, CurrencyCode.MXN),
            valueDate,
            "SPEI-" + UUID.randomUUID(),
            "ops-e2e"));

    String file = contributionFile(employeeCents, employerCents);
    PresignedUpload slot =
        batches.createUploadSlot(organizationId, PLAN_ID, PAYROLL_PERIOD, "acme-e2e.csv");
    assertThat(put(slot.url(), file)).as("upload to the presigned slot").isEqualTo(200);

    ContributionBatch batch =
        batches.submit(
            organizationId,
            slot.objectKey(),
            sha256Hex(file),
            new BatchDeclaration(
                PLAN_ID,
                PAYROLL_PERIOD,
                valueDate,
                totalCents,
                1,
                Map.of(
                    ContributionSource.EMPLOYEE, employeeCents,
                    ContributionSource.EMPLOYER, employerCents,
                    ContributionSource.VOLUNTARY, 0L)));

    validation.sweep();
    ContributionBatch staged = batches.get(organizationId, batch.batchId());
    assertThat(staged.status())
        .as("the batch validated and staged: %s", staged.rejection())
        .isEqualTo(ContributionBatchStatus.STAGED);

    // The date the batch actually deals at, re-derived the way allotment-job derives it rather
    // than assumed to be the value date: a batch staging after the day's cut-off deals on the
    // next business date, and a price struck for the wrong date would leave it unallotted.
    LocalDate dealingDate =
        DealingDateRule.dealingDateFor(
            staged.declared().valueDate(),
            staged.stagedAt().orElseThrow(),
            dealingLagBusinessDays,
            calendar);
    striker.strike(stack, fundId, dealingDate);

    stack.allotmentJobBean(AllotmentRunner.class).sweep();
    assertThat(statusOf(jdbc, batch.batchId()))
        .as(
            "the batch allotted: the sweep logs and swallows a failure, so without this the next"
                + " symptom would be a convergence timeout a minute later, describing nothing")
        .isEqualTo(ContributionBatchStatus.ALLOTTED.name());

    ParticipantId participantId = resolveParticipant(stack, organizationId);
    return new Scenario(
        organizationId,
        fundId,
        unitLedgerId,
        PARTICIPANT_REF,
        participantId,
        valueDate,
        dealingDate,
        navScaled,
        employeeCents,
        employerCents);
  }

  /**
   * What the employer's {@code contributionsThisPeriodCents} should be for this scenario — read
   * from the API's own window rather than assumed to contain the allotment.
   *
   * <p>"This period" is the calendar month of the value date (Task 6.5), and a batch that stages
   * after the day's cut-off on a month's last business day deals, and is counted, in the next one.
   * On that one day a month the honest expectation is zero. Taking the window from the summary and
   * the date from the movement history keeps the test right on that day instead of failing on it,
   * and is not circular: neither figure read here is the contributions total being checked.
   */
  long contributionsInPeriod(QueryApi query) {
    QueryApi.Response summary = query.summary(organizationId);
    LocalDate periodStart = LocalDate.parse(summary.json().get("periodStart").asText());
    LocalDate periodEnd = LocalDate.parse(summary.json().get("periodEnd").asText());
    LocalDate allottedOn =
        LocalDate.parse(
            query.movements(participantId).json().get("items").get(0).get("valueDate").asText());
    return allottedOn.isBefore(periodStart) || allottedOn.isAfter(periodEnd) ? 0L : totalCents();
  }

  /**
   * The batch's status as {@code allotment-job} left it — read from the row, not from a service.
   */
  private static String statusOf(JdbcClient jdbc, UUID batchId) {
    return jdbc.sql("SELECT status FROM workflow.contribution_batch WHERE batch_id = :batchId")
        .param("batchId", batchId)
        .query(String.class)
        .single();
  }

  /**
   * An employer nobody has registered before, through the service that owns organizations — which
   * also opens its {@code EMPLOYER_COLLECTIONS} ledger account, as a real onboarding does.
   *
   * <p>A fresh {@code RFC} every time: fixtures are reused across runs and TigerBeetle cannot be
   * reset (docs/ENGINEERING-RULES.md §4), so a fixed identifier would have the second run assert
   * against an organization and a ledger account the first one created.
   */
  static OrganizationId registerOrganization(PlatformStack stack) {
    return stack
        .contributionApiBean(OrganizationService.class)
        .register(
            new RegisterOrganizationCommand(
                TaxIdentifier.of(TaxIdScheme.MX_RFC, freshRfc()),
                "Acme S.A. de C.V.",
                Optional.empty(),
                Optional.empty()))
        .organization()
        .organizationId();
  }

  /**
   * A fund on its own unit ledger, published through {@link FundRepository} — which writes both
   * {@code reference.fund_identity} and {@code reference.fund}, the two rows every reader joins.
   */
  static FundId registerFund(PlatformStack stack, long unitLedgerId) {
    FundId fundId = FundId.of(UUID.randomUUID());
    stack
        .contributionApiBean(FundRepository.class)
        .publish(
            new Fund(
                fundId,
                LocalDate.of(2020, 1, 1),
                1,
                "Caja de Ahorro " + fundId.value(),
                "CAJA_DE_AHORRO",
                CurrencyCode.MXN,
                unitLedgerId,
                Units.SCALE,
                LocalDate.of(2020, 1, 1),
                Nav.of(TEN_PESOS_PER_UNIT, CurrencyCode.MXN),
                FundStatus.ACTIVE,
                Instant.now().truncatedTo(ChronoUnit.MILLIS)));
    return fundId;
  }

  /**
   * Strikes a price for this scenario's fund and refreshes the reader's cache, so a later query
   * values the holding at it. Used both for the dealing-date price and for a later, lower one.
   */
  static void strikeAndPublish(
      PlatformStack stack, FundId fundId, LocalDate valuationDate, long navScaled) {
    strikeNav(stack.contributionApiBean(JdbcClient.class), fundId, valuationDate, navScaled);
    stack.balanceApiBean(InMemoryNavCache.class).refreshAll();
  }

  /** Makes whatever prices are on record visible to {@code balance-api} now, not on its timer. */
  static void refreshPrices(PlatformStack stack) {
    stack.balanceApiBean(InMemoryNavCache.class).refreshAll();
  }

  static void strikeNav(JdbcClient jdbc, FundId fundId, LocalDate valuationDate, long navScaled) {
    jdbc.sql(
            """
            INSERT INTO reference.fund_nav (
                fund_id, valuation_date, nav_scaled, units_in_issue, source, published_at)
            VALUES (:fundId, :valuationDate, :navScaled, 0, 'MODEL_B', :now)
            """)
        .param("fundId", fundId.value())
        .param("valuationDate", valuationDate)
        .param("navScaled", navScaled)
        .param("now", Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS)))
        .update();
  }

  /**
   * The participant {@code contribution-api} enrolled while staging the file (Task 2b.3), and the
   * {@code participant_id} the column default assigned it — the key every Phase 6 endpoint is
   * addressed by. Resolved through {@code GET .../participants} (Task D.0), over real HTTP against
   * the running service, rather than the SQL this module used before that endpoint existed.
   */
  private static ParticipantId resolveParticipant(
      PlatformStack stack, OrganizationId organizationId) {
    URI uri =
        URI.create(
            stack.contributionApiBaseUrl()
                + "/v1/organizations/"
                + organizationId.value()
                + "/participants?internalRef="
                + PARTICIPANT_REF);
    try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      HttpResponse<String> response =
          http.send(
              HttpRequest.newBuilder(uri)
                  .timeout(Duration.ofSeconds(20))
                  .header("Accept", "application/json")
                  .GET()
                  .header("Authorization", PlatformStack.internalServiceAuthorization())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode())
          .as("participant lookup for internalRef=%s: %s", PARTICIPANT_REF, response.body())
          .isEqualTo(200);
      JsonNode body = MAPPER.readTree(response.body());
      return ParticipantId.parse(body.get("participantId").asText());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted resolving the participant", e);
    } catch (Exception e) {
      throw new IllegalStateException("could not resolve the participant", e);
    }
  }

  /** One participant, split at the plan's own 1:2 employer matching ratio. */
  private static String contributionFile(long employeeCents, long employerCents) {
    return ContributionFileParser.columnHeaderLine()
        + "\n"
        + PARTICIPANT_REF
        + ",ACME-EMP-0142,2019-03-01,2026-01-15,"
        + employeeCents
        + ","
        + employerCents
        + ",0\n";
  }

  /** A well-formed persona-moral RFC nobody has used: three letters, a date, three homoclave. */
  private static String freshRfc() {
    String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    String alphanumeric = letters + "0123456789";
    StringBuilder rfc = new StringBuilder(12);
    for (int i = 0; i < 3; i++) {
      rfc.append(letters.charAt(ThreadLocalRandom.current().nextInt(letters.length())));
    }
    rfc.append("010101");
    for (int i = 0; i < 3; i++) {
      rfc.append(alphanumeric.charAt(ThreadLocalRandom.current().nextInt(alphanumeric.length())));
    }
    return rfc.toString();
  }

  static int put(URI url, String content) {
    try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      return http.send(
              HttpRequest.newBuilder(url)
                  .timeout(Duration.ofSeconds(20))
                  .PUT(HttpRequest.BodyPublishers.ofString(content, StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.discarding())
          .statusCode();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted uploading the contribution file", e);
    } catch (Exception e) {
      throw new IllegalStateException("could not upload the contribution file", e);
    }
  }

  static String sha256Hex(String content) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}

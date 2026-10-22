package dev.fondaria.contribution.fund.web;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundRepository;
import dev.fondaria.rules.FundStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fund registration — one of the two things Task D.0 exists to add: until now only {@code
 * Scenario.registerFund}, reaching directly into {@link FundRepository}, could bring a fund into
 * existence. This is the same operation as product surface, not a demo hack.
 *
 * <p><b>The caller never chooses {@code unitLedgerId}.</b> A fresh registration gets the next
 * unused id in {@code [1001, 2,000,000,000)}, picked at random and retried on the rare collision —
 * the same range {@code Scenario.TargetFund.fresh} already draws from — because {@code
 * reference.fund_identity.unit_ledger_id} is unique across every fund that has ever existed
 * (AGENTS.md §4: a fund is never split across TigerBeetle clusters). A later version of an
 * <i>existing</i> fund reuses the id already on record rather than risking a second, discarded
 * allocation: {@link FundRepository#publish}'s identity insert is {@code ON CONFLICT DO NOTHING},
 * so whatever this controller passed for the identity fields on a second call would otherwise be
 * silently ignored by PostgreSQL while still being echoed back in the response.
 *
 * <p><b>Idempotent on {@code (fundId, version)}.</b> Replaying the identical request is a {@code
 * 200} carrying the row already on record; the same key with different content is a {@code 409} —
 * the one case where a replay is not simply the same statement made twice.
 */
@RestController
@RequestMapping("/v1/funds")
public class FundController {

  /** {@code LedgerCodes}: unit ledgers are assigned from 1001. */
  private static final long MIN_UNIT_LEDGER_ID = 1_001L;

  /**
   * Comfortably under TigerBeetle's ledger field width (u32), matching {@code TargetFund.fresh}.
   */
  private static final long MAX_UNIT_LEDGER_ID = 2_000_000_000L;

  private static final int MAX_UNIT_LEDGER_ID_ATTEMPTS = 20;

  /**
   * Far enough forward that resolving as of it returns the latest version ever published for a
   * fund, regardless of {@code effectiveFrom} — short of PostgreSQL's {@code date} ceiling.
   */
  private static final LocalDate FAR_FUTURE = LocalDate.of(9999, 12, 31);

  private final FundRepository funds;
  private final Clock clock;

  public FundController(FundRepository funds, Clock clock) {
    this.funds = funds;
    this.clock = clock;
  }

  @PostMapping
  public ResponseEntity<FundResponse> register(@RequestBody RegisterFundRequest request) {
    FundId fundId = request.parsedFundId().orElseGet(() -> FundId.of(UUID.randomUUID()));
    LocalDate effectiveFrom = request.parsedEffectiveFrom();
    int version = request.parsedVersion();

    Optional<Fund> atRequestedKey = funds.resolveAsOf(fundId, effectiveFrom);
    if (isExactKey(atRequestedKey, effectiveFrom, version)) {
      Fund existing = atRequestedKey.orElseThrow();
      if (matchesRequest(existing, request)) {
        return ResponseEntity.ok(FundResponse.of(existing));
      }
      throw new FundVersionConflictException(fundId, version);
    }

    Optional<Fund> anyVersion = funds.resolveAsOf(fundId, FAR_FUTURE);
    Fund published =
        anyVersion.isPresent()
            ? publishNewVersion(request, fundId, effectiveFrom, version, anyVersion.get())
            : registerWithFreshLedgerId(request, fundId, effectiveFrom, version);

    return ResponseEntity.status(HttpStatus.CREATED).body(FundResponse.of(published));
  }

  private static boolean isExactKey(Optional<Fund> resolved, LocalDate effectiveFrom, int version) {
    return resolved
        .filter(f -> f.effectiveFrom().equals(effectiveFrom) && f.version() == version)
        .isPresent();
  }

  /** Whether the caller's own submitted fields match the row already on record. */
  private static boolean matchesRequest(Fund existing, RegisterFundRequest request) {
    return existing.name().equals(request.parsedName())
        && existing.vehicleType().equals(request.parsedVehicleType())
        && matchesIdentity(existing, request);
  }

  /** Whether the caller's own submitted identity fields match a fund already on record. */
  private static boolean matchesIdentity(Fund identity, RegisterFundRequest request) {
    return identity.baseCurrency() == request.parsedCurrency()
        && identity.unitScale() == request.parsedUnitScale()
        && identity.inceptionDate().equals(request.parsedLaunchDate())
        && identity.openingNav().scaled() == request.parsedLaunchNavScaled();
  }

  /**
   * A later version of an existing fund. The identity fields are fixed forever at first
   * registration ({@link FundRepository#publish}'s own contract) — a request that disagrees with
   * them is rejected rather than silently overridden and echoed back as though it had been
   * honoured, which is what carrying the request's own (wrong) values forward would do since {@code
   * publish}'s identity insert is {@code ON CONFLICT DO NOTHING} and returns the candidate as
   * given, never re-read from the row PostgreSQL actually kept.
   */
  private Fund publishNewVersion(
      RegisterFundRequest request,
      FundId fundId,
      LocalDate effectiveFrom,
      int version,
      Fund identity) {
    if (!matchesIdentity(identity, request)) {
      throw new InvalidFundRequestException(
          "fund "
              + fundId
              + "'s identity (currency, unitScale, launchDate, launchNavScaled) was fixed at"
              + " first registration and cannot change in a later version");
    }
    Fund candidate =
        candidate(
            request,
            fundId,
            effectiveFrom,
            version,
            identity.unitLedgerId(),
            identity.baseCurrency(),
            identity.unitScale(),
            identity.inceptionDate(),
            identity.openingNav().scaled(),
            clock.instant());
    try {
      return funds.publish(candidate);
    } catch (DataIntegrityViolationException e) {
      // (fundId, effectiveFrom, version) was already taken by a version this call's as-of
      // resolution could not see — resolveAsOf only ever returns the highest version at or
      // before a date, so replaying an earlier version superseded by a later one at the same
      // effectiveFrom lands here. A genuine conflict, not something a new attempt can fix.
      throw new FundVersionConflictException(fundId, version);
    }
  }

  /**
   * A brand-new fund. The identity fields — including the ledger id — come from the request itself,
   * since nothing is on record yet to reuse instead.
   */
  private Fund registerWithFreshLedgerId(
      RegisterFundRequest request, FundId fundId, LocalDate effectiveFrom, int version) {
    CurrencyCode currency = request.parsedCurrency();
    long unitScale = request.parsedUnitScale();
    LocalDate launchDate = request.parsedLaunchDate();
    long launchNavScaled = request.parsedLaunchNavScaled();

    DataIntegrityViolationException lastFailure = null;
    for (int attempt = 0; attempt < MAX_UNIT_LEDGER_ID_ATTEMPTS; attempt++) {
      long unitLedgerId =
          ThreadLocalRandom.current().nextLong(MIN_UNIT_LEDGER_ID, MAX_UNIT_LEDGER_ID);
      Fund candidate =
          candidate(
              request,
              fundId,
              effectiveFrom,
              version,
              unitLedgerId,
              currency,
              unitScale,
              launchDate,
              launchNavScaled,
              clock.instant());
      try {
        return funds.publish(candidate);
      } catch (DataIntegrityViolationException e) {
        if (!isUnitLedgerIdCollision(e)) {
          // Some other constraint fired -- most plausibly (fundId, effectiveFrom, version)
          // itself, published concurrently by another request. A different random ledger id
          // cannot fix that, and burning every remaining attempt would only misreport the cause.
          throw new FundVersionConflictException(fundId, version);
        }
        // Another fund already holds this unit ledger id; try a different one.
        lastFailure = e;
      }
    }
    throw new IllegalStateException(
        "could not allocate a free unit ledger id after "
            + MAX_UNIT_LEDGER_ID_ATTEMPTS
            + " attempts",
        lastFailure);
  }

  /**
   * Distinguishes the one constraint a different random id actually resolves from every other cause
   * a {@link DataIntegrityViolationException} could have here.
   */
  private static boolean isUnitLedgerIdCollision(DataIntegrityViolationException e) {
    Throwable mostSpecific = e.getMostSpecificCause();
    String message = mostSpecific.getMessage();
    return message != null && message.contains("fund_identity_unit_ledger_id_unique");
  }

  private static Fund candidate(
      RegisterFundRequest request,
      FundId fundId,
      LocalDate effectiveFrom,
      int version,
      long unitLedgerId,
      CurrencyCode currency,
      long unitScale,
      LocalDate launchDate,
      long launchNavScaled,
      Instant publishedAt) {
    try {
      return new Fund(
          fundId,
          effectiveFrom,
          version,
          request.parsedName(),
          request.parsedVehicleType(),
          currency,
          unitLedgerId,
          unitScale,
          launchDate,
          Nav.of(launchNavScaled, currency),
          FundStatus.ACTIVE,
          publishedAt);
    } catch (IllegalArgumentException e) {
      throw new InvalidFundRequestException(e.getMessage());
    }
  }
}

package dev.fondaria.eodjob.custodian;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One disagreement between a {@link ProviderAccount}'s statement and this platform's own figures,
 * tracked as a case rather than a one-shot finding: opened once, re-observed on later passes, and
 * carried through assignment and investigation until it resolves or escalates.
 *
 * @param driftScaled ledger minus provider-reported (minus the explained pending-trade amount, for
 *     a timing difference), signed and unclamped — the ledger holding less is exactly as real a
 *     finding as holding more (AGENTS.md §4). Never zero; never adjusted to force agreement.
 * @param firstDetectedBusinessDate when this break was opened; age is measured from here
 * @param lastConfirmedBusinessDate the most recent pass that still found this break present
 */
public record CustodianBreak(
    UUID breakId,
    UUID providerAccountId,
    BreakCategory category,
    BreakStatus status,
    long driftScaled,
    LocalDate firstDetectedBusinessDate,
    LocalDate lastConfirmedBusinessDate,
    Optional<String> assignedTo,
    Instant openedAt,
    Instant statusUpdatedAt,
    Optional<Instant> escalatedAt,
    Optional<Instant> resolvedAt,
    Optional<String> resolutionNote) {

  public CustodianBreak {
    Objects.requireNonNull(breakId, "breakId must not be null");
    Objects.requireNonNull(providerAccountId, "providerAccountId must not be null");
    Objects.requireNonNull(category, "category must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(firstDetectedBusinessDate, "firstDetectedBusinessDate must not be null");
    Objects.requireNonNull(lastConfirmedBusinessDate, "lastConfirmedBusinessDate must not be null");
    Objects.requireNonNull(assignedTo, "assignedTo must not be null");
    Objects.requireNonNull(openedAt, "openedAt must not be null");
    Objects.requireNonNull(statusUpdatedAt, "statusUpdatedAt must not be null");
    Objects.requireNonNull(escalatedAt, "escalatedAt must not be null");
    Objects.requireNonNull(resolvedAt, "resolvedAt must not be null");
    Objects.requireNonNull(resolutionNote, "resolutionNote must not be null");
    if (driftScaled == 0) {
      throw new IllegalArgumentException("a break requires a non-zero drift");
    }
    if (lastConfirmedBusinessDate.isBefore(firstDetectedBusinessDate)) {
      throw new IllegalArgumentException(
          "lastConfirmedBusinessDate precedes firstDetectedBusinessDate");
    }
    if (status.isTerminal() != resolvedAt.isPresent()) {
      throw new IllegalArgumentException(
          "resolvedAt must be present if and only if status is RESOLVED");
    }
  }

  /** Days since this break was first detected, as of {@code businessDate}. */
  public long ageInDays(LocalDate businessDate) {
    return ChronoUnit.DAYS.between(firstDetectedBusinessDate, businessDate);
  }
}

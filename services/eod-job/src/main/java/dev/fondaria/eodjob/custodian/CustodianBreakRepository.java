package dev.fondaria.eodjob.custodian;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface CustodianBreakRepository {

  /** The non-terminal break for this account and category, if one is currently open. */
  Optional<CustodianBreak> findOpen(UUID providerAccountId, BreakCategory category);

  /** Opens a new break, {@link BreakStatus#OPEN}. */
  CustodianBreak open(
      UUID providerAccountId,
      BreakCategory category,
      long driftScaled,
      LocalDate businessDate,
      Instant now);

  /**
   * Re-observes an already-open break: updates its drift and last-confirmed date, not its status.
   */
  void confirmStillOpen(UUID breakId, long driftScaled, LocalDate businessDate, Instant now);

  /**
   * Closes a break because the underlying condition cleared — never because units were adjusted.
   * Does not touch {@code last_confirmed_business_date}: the resolving pass did not find the break
   * present, so recording it as "confirmed" on this date would overstate how long it was actually
   * live by one pass.
   */
  void resolve(UUID breakId, Instant now, String resolutionNote);

  void assign(UUID breakId, String assignedTo, Instant now);

  void investigate(UUID breakId, Instant now);

  /**
   * Transitions every non-terminal, non-escalated break past its category's age threshold to {@link
   * BreakStatus#ESCALATED}. Returns how many were escalated.
   */
  int escalateAged(
      LocalDate businessDate, int realBreakThresholdDays, int timingThresholdDays, Instant now);

  /**
   * Whether any real (non-timing-difference) break is currently open, assigned or investigating.
   */
  boolean hasOpenRealBreak();
}

package dev.fondaria.eodjob.snapshot;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The snapshot for a business date did not produce a file the platform may stand behind.
 *
 * <p>The message is what {@code workflow.snapshot_run.failure_reason} records and what an operator
 * reads, so it is written in their words and carries no exception text, no SQL and no hostname
 * (docs/ENGINEERING-RULES.md §7). It names the date, because the first question about a failed
 * close is always which one.
 *
 * <p>Two failures are distinct enough to be caught separately and have their own subclasses: {@link
 * SnapshotMismatchException} (a re-run disagreeing with a snapshot already taken) and {@link
 * SnapshotNotTiedException} (a fund whose file total does not equal its units in issue). This class
 * itself covers the rest — most importantly a fund holding positions that has no price on or before
 * the date, which cannot be valued and must never be valued at zero.
 */
public class SnapshotFailedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient LocalDate businessDate;
  private final transient String reason;

  public SnapshotFailedException(LocalDate businessDate, String reason) {
    super("the snapshot for " + businessDate + " failed: " + reason);
    this.businessDate = Objects.requireNonNull(businessDate, "businessDate must not be null");
    this.reason = Objects.requireNonNull(reason, "reason must not be null");
  }

  public LocalDate businessDate() {
    return businessDate;
  }

  /**
   * The operator-facing sentence, as {@code workflow.snapshot_run.failure_reason} stores it.
   *
   * <p>Held as its own field rather than sliced back out of the message: the reasons here contain
   * colons of their own, and a failure whose recorded explanation depended on where the first one
   * happened to fall would be silently truncated on exactly the most detailed findings.
   */
  public String reason() {
    return reason;
  }
}

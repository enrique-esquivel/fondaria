package dev.fondaria.eodjob.signoff;

import java.time.LocalDate;
import java.util.Objects;

/**
 * What one sign-off pass did for a business date.
 *
 * @param signoff the {@code workflow.close_signoff} row — freshly written, or the one that already
 *     existed
 * @param newlySignedOff {@code true} if this pass wrote the row; {@code false} if the date was
 *     already signed off (by an earlier pass, or another instance)
 * @param announced whether {@code close.signed-off.v1} is confirmed for this date. {@code false}
 *     means the row is written but the event is not out yet — a broker outage — and the next sweep
 *     will re-publish.
 */
public record SignoffResult(CloseSignoff signoff, boolean newlySignedOff, boolean announced) {

  public SignoffResult {
    Objects.requireNonNull(signoff, "signoff must not be null");
  }

  public LocalDate businessDate() {
    return signoff.businessDate();
  }
}

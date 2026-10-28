package dev.fondaria.eodjob.signoff;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The final gate of the daily close: <b>nothing outward-facing renders for a date that has not been
 * signed off</b> (Task 7.5a).
 *
 * <p>The fourth in the sequence {@code ReconciliationGate} (7.2) → {@code
 * CustodianReconciliationGate} (7.3) → {@link SnapshotGate} (7.4) → this. Where those three each
 * read a run's {@code status}, this one is simpler: a {@code workflow.close_signoff} row exists iff
 * {@link SignoffService} found all three of the others open. A row means signed off.
 *
 * <p><b>It fails closed the one way it can.</b> No row for the date blocks. There is no {@code
 * RUNNING} or {@code FAILED} equivalent — sign-off is not a lifecycle — so "not signed off" is the
 * only closed state, and it is the default.
 *
 * <p><b>Task 7.5b's {@code statement-worker} does not call this class.</b> It is a separate service
 * and does not depend on {@code eod-job}'s source tree ({@code ADR-0021}); it reads {@code
 * workflow.close_signoff} through its own repository. This gate is {@code eod-job}'s own, for any
 * later close step that must not run before sign-off — and the named contract the phase document
 * calls "the sign-off gate".
 */
@Component
public class SignoffGate {

  private final SignoffRepository signoffs;

  public SignoffGate(SignoffRepository signoffs) {
    this.signoffs = Objects.requireNonNull(signoffs, "signoffs must not be null");
  }

  /** Whether this date's close has been signed off. */
  public boolean isSignedOff(LocalDate businessDate) {
    return signoffs.find(businessDate).isPresent();
  }

  /** The latest business date that has been signed off, if any. */
  public Optional<LocalDate> latestSignedOff() {
    return signoffs.latestSignedOffDate();
  }

  /**
   * The sign-off for this date.
   *
   * @throws CloseNotSignedOffException if the date has no {@code workflow.close_signoff} row
   */
  public CloseSignoff requireSignedOff(LocalDate businessDate) {
    return signoffs
        .find(businessDate)
        .orElseThrow(() -> new CloseNotSignedOffException(businessDate));
  }
}

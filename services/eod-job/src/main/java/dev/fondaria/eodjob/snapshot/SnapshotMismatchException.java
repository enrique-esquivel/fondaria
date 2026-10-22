package dev.fondaria.eodjob.snapshot;

import java.time.LocalDate;

/**
 * A re-run for a date already snapshotted produced a different file.
 *
 * <p>phase-07 Task 7.4: <i>a re-run produces an identical hash, or halts on mismatch — never
 * silently overwrites.</i> This is the halt. The existing object is left exactly as it was and the
 * new bytes are discarded unwritten, which is the point: the snapshot of record for a date is
 * whatever was agreed the first time, and a second, differing answer is a question for a human, not
 * a replacement.
 *
 * <p>It is not automatically a fault. A snapshot taken before the day's last allotment landed will
 * legitimately differ from one taken after, and the honest resolution is to decide which is the
 * close — not to let the later pass quietly become it.
 */
public class SnapshotMismatchException extends SnapshotFailedException {

  private static final long serialVersionUID = 1L;

  private final transient String establishedSha256;
  private final transient String recomputedSha256;

  public SnapshotMismatchException(
      LocalDate businessDate, String establishedSha256, String recomputedSha256) {
    super(
        businessDate,
        "a snapshot already exists for this date with content hash "
            + establishedSha256
            + ", and this pass produced "
            + recomputedSha256
            + "; the stored file was left untouched");
    this.establishedSha256 = establishedSha256;
    this.recomputedSha256 = recomputedSha256;
  }

  public String establishedSha256() {
    return establishedSha256;
  }

  public String recomputedSha256() {
    return recomputedSha256;
  }
}

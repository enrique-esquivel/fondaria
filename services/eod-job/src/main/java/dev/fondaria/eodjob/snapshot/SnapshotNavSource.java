package dev.fondaria.eodjob.snapshot;

import dev.fondaria.common.FundId;
import java.time.LocalDate;
import java.util.Map;

/**
 * The prices a snapshot values positions at: one live NAV per fund, as of a business date.
 *
 * <p>Read-only, and narrower than {@code FundNavRepository} in {@code libs/rules-engine} on purpose
 * — the same narrowing {@code FundDirectory} applies to funds. The close must never strike or
 * correct a price; it reads what valuation-api recorded. eod-job depends on no rule engine at all
 * (see its {@code pom.xml}), so this is the whole of what it needs.
 */
public interface SnapshotNavSource {

  /**
   * The live NAV for every fund that has one on or before {@code businessDate}, keyed by fund.
   *
   * <p><b>Live, not original.</b> A corrected NAV supersedes the row it names, and a snapshot must
   * value at the price the platform currently stands behind — the row nothing points at (Task 20.5,
   * {@code KEY-TYPES.md}). A fund with no price on or before the date is simply absent from the
   * map; {@code SnapshotService} fails the pass rather than valuing it at zero, because a fund
   * priced at zero and a fund never priced are different facts and only one of them is a number.
   */
  Map<FundId, SnapshotNav> liveNavsAsOf(LocalDate businessDate);
}

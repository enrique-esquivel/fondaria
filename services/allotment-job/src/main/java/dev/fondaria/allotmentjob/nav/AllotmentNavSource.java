package dev.fondaria.allotmentjob.nav;

import dev.fondaria.common.FundId;
import dev.fondaria.rules.FundNav;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Read-only access to {@code reference.fund_nav} (Task 20.4), narrower than {@code rules-engine}'s
 * {@code FundNavRepository} on purpose — that port carries {@code strike}, and this service must
 * never strike a NAV; that is valuation-api's alone.
 *
 * <p><b>Deliberately {@link #findOriginal}, never {@code findLatestLive}.</b> A batch must be
 * priced at the NAV struck for its own dealing date, not at whatever the most recent live price
 * happens to be — a batch dealt three days ago must still allot at that day's price even if the
 * fund has moved since. {@code FundNavRepository}'s own javadoc draws this exact distinction:
 * {@code findLatestLive} answers "the price a balance read should multiply by today", which is the
 * wrong question here.
 */
public interface AllotmentNavSource {

  /**
   * The original (non-superseding) strike for {@code fundId} on {@code valuationDate}, if one has
   * been recorded. Absent means the dealing date has not been struck yet — the batch stays STAGED
   * and is retried next sweep, never allotted at a substitute price.
   */
  Optional<FundNav> findOriginal(FundId fundId, LocalDate valuationDate);
}

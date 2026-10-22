package dev.fondaria.eodjob.snapshot;

import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The price a fund's positions are valued at for one business date, and which {@code
 * reference.fund_nav} row it came from.
 *
 * @param valuationDate the date the price was struck for, which is at or before the snapshot's
 *     business date and is often earlier — a fund is not struck on a day the market did not price
 *     it, and the last struck price is the right one to carry forward
 * @param navVersionId the row this price is, recorded on every position. docs/ENGINEERING-RULES.md
 *     §4: without the link from an amount to the NAV version that produced it, correcting a NAV
 *     error is impossible — and a snapshot is the one artefact a correction has to be replayed
 *     against.
 */
public record SnapshotNav(FundId fundId, long navVersionId, Nav nav, LocalDate valuationDate) {

  public SnapshotNav {
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(nav, "nav must not be null");
    Objects.requireNonNull(valuationDate, "valuationDate must not be null");
  }
}

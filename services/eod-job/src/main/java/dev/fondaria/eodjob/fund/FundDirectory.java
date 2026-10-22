package dev.fondaria.eodjob.fund;

import java.util.List;

/**
 * Read-only access to the fund registry (Task 20.1).
 *
 * <p>Narrower than {@code rules-engine}'s {@code FundRepository} on purpose, the same reason
 * valuation-api's and allotment-job's directories of the same name are: that port carries {@code
 * publish}, and the daily close must never publish a fund. Narrower than either of those too — the
 * close needs no as-of resolution, because a fund's identity and unit ledger are fixed at
 * registration and are the only things a tie-out asks about.
 */
public interface FundDirectory {

  /** Every registered fund, in a stable order so two runs report their breaks the same way. */
  List<RegisteredFund> allFunds();
}

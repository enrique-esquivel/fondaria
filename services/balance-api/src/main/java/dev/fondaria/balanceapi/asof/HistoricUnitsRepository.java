package dev.fondaria.balanceapi.asof;

import java.time.LocalDate;
import java.util.List;

/**
 * Reads the sparse {@code projection.participant_units} for a participant's holdings as at a past
 * date (Task 6.4).
 *
 * <p><b>This is the second query endpoint allowed to read PostgreSQL, and it is not a contradiction
 * of Task 6.2.</b> That rule is about a <i>current</i> balance, whose authority is the ledger
 * (ADR-0008). The ledger has no concept of "as of": {@code lookupAccount} returns what an account
 * holds now, and reconstructing a past holding from it would mean replaying every transfer. The
 * sparse projection exists for exactly this question (docs/DOMAIN.md) and this read is low-volume
 * and batch-shaped, which is why the phase document allows it here and nowhere else.
 *
 * <p>Nothing here sums anything, and nothing here is a balance: a row is an absolute holding
 * recorded by the CDC consumer, not an accumulation this service performs.
 */
public interface HistoricUnitsRepository {

  /**
   * The latest holding at or before {@code asOfDate} for each of {@code accounts}, in no particular
   * order.
   *
   * <p><b>An account with no row at or before that date is absent from the result, never returned
   * as zero.</b> The two are different claims — "held nothing then" versus "there is no record this
   * far back" — and only the caller has the context to turn the second into a {@code 404}.
   *
   * @param tenantId the partition key — the organization's {@code TenantRef} as unsigned decimal
   */
  List<HistoricHolding> holdingsAsOf(
      String tenantId, List<HoldingAccountKey> accounts, LocalDate asOfDate);
}

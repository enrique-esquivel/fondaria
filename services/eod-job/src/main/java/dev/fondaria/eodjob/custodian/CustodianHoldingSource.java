package dev.fondaria.eodjob.custodian;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.eodjob.reconciliation.HoldingKey;
import java.util.List;

/**
 * The holdings an {@link ProviderScopeType#ORGANIZATION}-scoped {@link ProviderAccount} sums — one
 * organization's participants in one fund, from {@code workflow.allotment}.
 *
 * <p>{@link ProviderScopeType#ALL_PARTICIPANTS} needs none of this: it resolves straight to the
 * fund's {@code units_in_issue} account. This exists only for the segregated case, and reuses
 * {@link HoldingKey} — the same candidate-set key Task 7.2 sums platform-wide — rather than a
 * second copy of it, since both live in the same service (`ADR-0018`).
 */
public interface CustodianHoldingSource {

  List<HoldingKey> holdingsOf(OrganizationId organizationId, FundId fundId);
}

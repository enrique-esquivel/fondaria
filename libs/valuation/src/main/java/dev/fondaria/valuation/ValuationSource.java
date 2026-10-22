package dev.fondaria.valuation;

import dev.fondaria.common.FundId;
import java.time.LocalDate;

/**
 * We do not manage the portfolio. We receive a valuation from an external investment platform and
 * translate it into a price per unit (AGENTS.md §2, {@code docs/DOMAIN.md} §3).
 *
 * <p>Three adapters cover the realistic integration shapes, so a different provider is a new
 * adapter, never a rewrite of this port (ADR-0004):
 *
 * <ul>
 *   <li><b>A — mirror external units.</b> The provider already runs a unitized fund and reports its
 *       own NAV per unit directly. Unimplemented.
 *   <li><b>B — unitize a reported balance.</b> The provider reports a total pooled balance; we
 *       strike our own NAV as {@code reported value ÷ units in issue}. The only model implemented
 *       in this phase — see {@link ReportedBalanceValuationSource}.
 *   <li><b>C — declared rate.</b> The provider reports a rate; NAV grows by it. Unimplemented.
 * </ul>
 */
public interface ValuationSource {

  Valuation valuationFor(FundId fund, LocalDate date);
}

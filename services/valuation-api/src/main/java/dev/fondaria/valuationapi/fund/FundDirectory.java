package dev.fondaria.valuationapi.fund;

import dev.fondaria.common.FundId;
import dev.fondaria.rules.Fund;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the fund registry {@code reference.fund} / {@code reference.fund_identity}
 * (Task 20.1).
 *
 * <p>Narrower than {@code rules-engine}'s {@code FundRepository} on purpose. That port carries
 * {@code publish}, and this service must never publish a fund: the registry is written by whoever
 * administers products, and a valuation arriving for an unregistered fund is a rejection, never an
 * invitation to create one. Implementing the wider port here would mean an unsupported method on
 * every implementation.
 */
public interface FundDirectory {

  /**
   * The fund version live on {@code asOfDate}, or empty when no fund is registered under this id at
   * that date.
   *
   * <p><b>As of the valuation's own date, never as of today.</b> Funds are effective-dated, so a
   * valuation delivered for a past date must be checked against the fund as it was on that date —
   * resolving against today would accept a valuation for a date before the fund existed.
   */
  Optional<Fund> resolveAsOf(FundId fundId, LocalDate asOfDate);

  /**
   * Every fund expected to be valued on {@code asOfDate}: registered, {@code ACTIVE}, and with an
   * inception date not after it. The population the ageing report walks.
   */
  List<Fund> activeAsOf(LocalDate asOfDate);
}

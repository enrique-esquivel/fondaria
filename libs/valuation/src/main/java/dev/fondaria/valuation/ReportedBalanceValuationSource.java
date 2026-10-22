package dev.fondaria.valuation;

import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundRepository;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Model B ({@code docs/DOMAIN.md} §3): unitize a reported balance. The only {@link ValuationSource}
 * implemented in this phase.
 *
 * <p>{@code unitsInIssue} is read exclusively through {@link LedgerPort#lookupAccount} — never
 * summed from PostgreSQL (ADR-0008). A fund whose {@code UNITS_IN_ISSUE} account has never been
 * opened (no allotment has run yet) looks up empty rather than zero-balance, and is treated as zero
 * units in issue: {@link NavPerUnit#strike} then returns the fund's declared opening price.
 */
public final class ReportedBalanceValuationSource implements ValuationSource {

  private static final String SOURCE = "MODEL_B";

  private final FundRepository funds;
  private final LedgerPort ledger;
  private final AccountIdDeriver accountIdDeriver;
  private final ReportedBalanceSource reportedBalances;

  public ReportedBalanceValuationSource(
      FundRepository funds,
      LedgerPort ledger,
      AccountIdDeriver accountIdDeriver,
      ReportedBalanceSource reportedBalances) {
    this.funds = Objects.requireNonNull(funds, "funds must not be null");
    this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
    this.accountIdDeriver =
        Objects.requireNonNull(accountIdDeriver, "accountIdDeriver must not be null");
    this.reportedBalances =
        Objects.requireNonNull(reportedBalances, "reportedBalances must not be null");
  }

  @Override
  public Valuation valuationFor(FundId fundId, LocalDate date) {
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(date, "date must not be null");

    Fund fund =
        funds
            .resolveAsOf(fundId, date)
            .orElseThrow(() -> new IllegalArgumentException("Fund not found: " + fundId));

    Money reportedValue = reportedBalances.reportedValueFor(fundId, date);
    Units unitsInIssue = unitsInIssue(fundId);
    NavPerUnit navPerUnit = NavPerUnit.strike(reportedValue, unitsInIssue, fund.openingNav());

    return new Valuation(fundId, date, reportedValue, navPerUnit, SOURCE);
  }

  private Units unitsInIssue(FundId fundId) {
    AccountId accountId =
        accountIdDeriver.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fundId.toString());
    return ledger
        .lookupAccount(accountId)
        .map(account -> ((LedgerAccount.Unit) account).balance())
        .orElseGet(() -> Units.of(0));
  }
}

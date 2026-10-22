package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import org.junit.jupiter.api.Test;

class LedgerAccountTest {

  @Test
  void cashBalanceMustBeMxn() {
    Money usd = Money.of(1_000L, CurrencyCode.USD);
    assertThatThrownBy(
            () ->
                new LedgerAccount.Cash(
                    AccountId.of(0L, 1L), LedgerCodes.AccountCode.FUND_CASH, false, usd))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MXN");
  }

  @Test
  void cashBalanceAcceptsMxn() {
    Money mxn = Money.of(1_000L, CurrencyCode.MXN);
    LedgerAccount.Cash account =
        new LedgerAccount.Cash(AccountId.of(0L, 1L), LedgerCodes.AccountCode.FUND_CASH, false, mxn);
    assertThat(account.balance()).isEqualTo(mxn);
  }

  @Test
  void unitBalanceIsNotCurrencyConstrained() {
    LedgerAccount.Unit account =
        new LedgerAccount.Unit(
            AccountId.of(0L, 1L),
            LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
            true,
            1001L,
            Units.of(100L));
    assertThat(account.fundLedgerId()).isEqualTo(1001L);
  }
}

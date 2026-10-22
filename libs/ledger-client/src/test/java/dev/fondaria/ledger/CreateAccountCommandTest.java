package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CreateAccountCommandTest {

  @Test
  void debitsMustNotExceedCreditsComesFromTheAccountCode() {
    CreateAccountCommand holdings =
        new CreateAccountCommand(
            AccountId.of(0L, 1L), 1001L, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED);
    CreateAccountCommand fundCash =
        new CreateAccountCommand(
            AccountId.of(0L, 2L), LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.FUND_CASH);

    assertThat(holdings.debitsMustNotExceedCredits()).isTrue();
    assertThat(fundCash.debitsMustNotExceedCredits()).isFalse();
  }

  @Test
  void rejectsANonPositiveLedgerId() {
    assertThatThrownBy(
            () ->
                new CreateAccountCommand(
                    AccountId.of(0L, 1L), 0L, LedgerCodes.AccountCode.FUND_CASH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ledgerId");
  }

  @Test
  void rejectsANullId() {
    assertThatThrownBy(
            () ->
                new CreateAccountCommand(
                    null, LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.FUND_CASH))
        .isInstanceOf(NullPointerException.class);
  }
}

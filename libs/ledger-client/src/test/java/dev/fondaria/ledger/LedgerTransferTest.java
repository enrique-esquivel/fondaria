package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LedgerTransferTest {

  private static final TransferId ID = TransferId.of(1L, 1L);
  private static final AccountId DEBIT = AccountId.of(0L, 1L);
  private static final AccountId CREDIT = AccountId.of(0L, 2L);

  @Test
  void cashAmountMustBeMxn() {
    Money usd = Money.of(1_000L, CurrencyCode.USD);
    assertThatThrownBy(
            () ->
                new LedgerTransfer.Cash(
                    ID,
                    DEBIT,
                    CREDIT,
                    LedgerCodes.TransferCode.CASH_RECEIPT,
                    usd,
                    Instant.EPOCH,
                    null,
                    ExternalReference.NONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MXN");
  }

  @Test
  void pendingIdDefaultsToEmptyWhenNull() {
    LedgerTransfer.Cash transfer =
        new LedgerTransfer.Cash(
            ID,
            DEBIT,
            CREDIT,
            LedgerCodes.TransferCode.CASH_RECEIPT,
            Money.of(1_000L, CurrencyCode.MXN),
            Instant.EPOCH,
            null,
            ExternalReference.NONE);
    assertThat(transfer.pendingId()).isEmpty();
  }

  @Test
  void pendingIdIsPresentWhenGiven() {
    TransferId originalPendingId = TransferId.of(9L, 9L);
    LedgerTransfer.Cash transfer =
        new LedgerTransfer.Cash(
            ID,
            DEBIT,
            CREDIT,
            LedgerCodes.TransferCode.CASH_RECEIPT,
            Money.of(1_000L, CurrencyCode.MXN),
            Instant.EPOCH,
            originalPendingId,
            ExternalReference.NONE);
    assertThat(transfer.pendingId()).contains(originalPendingId);
  }
}

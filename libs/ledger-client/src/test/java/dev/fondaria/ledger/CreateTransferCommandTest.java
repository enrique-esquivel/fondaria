package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.TenantRef;
import dev.fondaria.common.Units;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreateTransferCommandTest {

  private static final TransferId ID = TransferId.of(1L, 1L);
  private static final AccountId DEBIT = AccountId.of(0L, 1L);
  private static final AccountId CREDIT = AccountId.of(0L, 2L);
  private static final Money AMOUNT = Money.of(1_000L, CurrencyCode.MXN);

  @Test
  void singleNeedsNeitherPendingField() {
    CreateTransferCommand.Cash cmd =
        CreateTransferCommand.Cash.single(
            ID, DEBIT, CREDIT, LedgerCodes.TransferCode.CASH_RECEIPT, AMOUNT);
    assertThat(cmd.kind()).isEqualTo(TransferKind.SINGLE);
    assertThat(cmd.pendingTimeout()).isEmpty();
    assertThat(cmd.pendingId()).isEmpty();
  }

  @Test
  void pendingCarriesTheGivenTimeout() {
    CreateTransferCommand.Cash cmd =
        CreateTransferCommand.Cash.pending(
            ID,
            DEBIT,
            CREDIT,
            LedgerCodes.TransferCode.CASH_RECEIPT,
            AMOUNT,
            Duration.ofMinutes(5));
    assertThat(cmd.kind()).isEqualTo(TransferKind.PENDING);
    assertThat(cmd.pendingTimeout()).contains(Duration.ofMinutes(5));
    assertThat(cmd.pendingId()).isEmpty();
  }

  @Test
  void pendingRequiresATimeout() {
    assertThatThrownBy(
            () ->
                CreateTransferCommand.Cash.pending(
                    ID, DEBIT, CREDIT, LedgerCodes.TransferCode.CASH_RECEIPT, AMOUNT, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void postPendingCarriesTheOriginalPendingId() {
    TransferId originalPendingId = TransferId.of(9L, 9L);
    CreateTransferCommand.Cash cmd =
        CreateTransferCommand.Cash.postPending(
            ID, DEBIT, CREDIT, LedgerCodes.TransferCode.CASH_RECEIPT, AMOUNT, originalPendingId);
    assertThat(cmd.kind()).isEqualTo(TransferKind.POST_PENDING);
    assertThat(cmd.pendingId()).contains(originalPendingId);
    assertThat(cmd.pendingTimeout()).isEmpty();
  }

  @Test
  void voidPendingCarriesTheOriginalPendingId() {
    TransferId originalPendingId = TransferId.of(9L, 9L);
    CreateTransferCommand.Cash cmd =
        CreateTransferCommand.Cash.voidPending(
            ID, DEBIT, CREDIT, LedgerCodes.TransferCode.CASH_RECEIPT, AMOUNT, originalPendingId);
    assertThat(cmd.kind()).isEqualTo(TransferKind.VOID_PENDING);
    assertThat(cmd.pendingId()).contains(originalPendingId);
  }

  @Test
  void postPendingRequiresAPendingId() {
    assertThatThrownBy(
            () ->
                CreateTransferCommand.Cash.postPending(
                    ID, DEBIT, CREDIT, LedgerCodes.TransferCode.CASH_RECEIPT, AMOUNT, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void voidPendingRequiresAPendingId() {
    assertThatThrownBy(
            () ->
                CreateTransferCommand.Cash.voidPending(
                    ID, DEBIT, CREDIT, LedgerCodes.TransferCode.CASH_RECEIPT, AMOUNT, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void pendingTimeoutOnASingleTransferIsRejectedAtTheCanonicalConstructor() {
    // The static factories can't produce this shape; only a direct canonical-constructor call
    // (as an adapter might do when reconstructing a command) can, so it's checked there too.
    assertThatThrownBy(
            () ->
                new CreateTransferCommand.Cash(
                    ID,
                    DEBIT,
                    CREDIT,
                    LedgerCodes.TransferCode.CASH_RECEIPT,
                    AMOUNT,
                    TransferKind.SINGLE,
                    Duration.ofMinutes(5),
                    null,
                    ExternalReference.NONE,
                    TenantRef.NONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pendingTimeout");
  }

  @Test
  void aPendingIdOnASingleTransferIsRejectedAtTheCanonicalConstructor() {
    assertThatThrownBy(
            () ->
                new CreateTransferCommand.Cash(
                    ID,
                    DEBIT,
                    CREDIT,
                    LedgerCodes.TransferCode.CASH_RECEIPT,
                    AMOUNT,
                    TransferKind.SINGLE,
                    null,
                    TransferId.of(9L, 9L),
                    ExternalReference.NONE,
                    TenantRef.NONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pendingId");
  }

  @Test
  void tenantDefaultsToNoneAndIsCarriedByWithTenant() {
    CreateTransferCommand.Cash cash =
        CreateTransferCommand.Cash.single(
            ID, DEBIT, CREDIT, LedgerCodes.TransferCode.CASH_RECEIPT, AMOUNT);
    assertThat(cash.tenant()).isEqualTo(TenantRef.NONE);

    TenantRef acme = TenantRef.derive(UUID.randomUUID(), new OrganizationId(UUID.randomUUID()));
    assertThat(cash.withTenant(acme).tenant()).isEqualTo(acme);

    CreateTransferCommand.Unit unit =
        CreateTransferCommand.Unit.single(
            ID,
            DEBIT,
            CREDIT,
            1001L,
            LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT,
            Units.of(5L));
    assertThat(unit.tenant()).isEqualTo(TenantRef.NONE);
    assertThat(unit.withTenant(acme).tenant()).isEqualTo(acme);
  }

  @Test
  void cashAmountMustBeMxn() {
    Money usd = Money.of(1_000L, CurrencyCode.USD);
    assertThatThrownBy(
            () ->
                CreateTransferCommand.Cash.single(
                    ID, DEBIT, CREDIT, LedgerCodes.TransferCode.CASH_RECEIPT, usd))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MXN");
  }

  @Test
  void zeroAndNegativeAmountsAreNotRejectedAtConstructionTime() {
    // Business-rule rejections (zero/negative amount, self-transfer, insufficient balance,
    // cross-ledger) are the ledger's call, not the client's — see the class Javadoc. Both
    // adapters must return TransferResult.Rejected for these via the Task 2.6 conformance suite.
    CreateTransferCommand.Cash zero =
        CreateTransferCommand.Cash.single(
            ID,
            DEBIT,
            CREDIT,
            LedgerCodes.TransferCode.CASH_RECEIPT,
            Money.of(0L, CurrencyCode.MXN));
    CreateTransferCommand.Cash selfTransfer =
        CreateTransferCommand.Cash.single(
            ID, DEBIT, DEBIT, LedgerCodes.TransferCode.CASH_RECEIPT, AMOUNT);
    assertThat(zero.amount().isZero()).isTrue();
    assertThat(selfTransfer.debitAccountId()).isEqualTo(selfTransfer.creditAccountId());
  }
}

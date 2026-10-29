package dev.fondaria.ledger.tigerbeetle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.Client;
import com.tigerbeetle.IdBatch;
import com.tigerbeetle.TransferBatch;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.TenantRef;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerLimits;
import dev.fondaria.ledger.LedgerRejectionReason;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferResult;
import dev.fondaria.testing.TigerBeetleFixture;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link TigerBeetleLedgerAdapter} against a real TigerBeetle cluster (Task
 * 2.4 acceptance criteria). TigerBeetle is append-only, so — per {@link TigerBeetleFixture}'s own
 * warning — every test uses fresh account/transfer/fund-ledger ids rather than resetting state.
 *
 * <p>Duplicate-id, insufficient-units, cross-ledger and linked-chain-failure — also Task 2.4
 * acceptance criteria — are no longer asserted here: {@code
 * dev.fondaria.ledger.conformance.TigerBeetleLedgerPortConformanceTest} (Task 2.6) runs those same
 * scenarios against this exact adapter, written against {@code LedgerPort} rather than this class
 * directly. What remains below is what the conformance suite cannot express because it is specific
 * to this adapter's own mechanics: batching (not a single-transfer code path, even for one), and
 * this adapter's particular id byte-order convention.
 */
class TigerBeetleLedgerAdapterTest {

  private static final AtomicLong FUND_LEDGER_SEQ = new AtomicLong(2_000);

  private static Client client;
  private static TigerBeetleLedgerAdapter adapter;

  @BeforeAll
  static void setUpAll() {
    client = TigerBeetleFixture.getInstance().getClient();
    adapter =
        new TigerBeetleLedgerAdapter(client, Duration.ofSeconds(5), ObservationRegistry.create());
  }

  @AfterAll
  static void tearDownAll() {
    client.close();
  }

  @Test
  void createsCashAndUnitAccountsTransfersBothAndReadsBalancesBack() {
    AccountId bankClearing = freshAccountId();
    AccountId employerCollections = freshAccountId();
    adapter.createAccount(
        new CreateAccountCommand(
            bankClearing, LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.BANK_CLEARING));
    adapter.createAccount(
        new CreateAccountCommand(
            employerCollections,
            LedgerCodes.CASH_LEDGER_ID,
            LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS));

    Money remittance = Money.of(750_000L, CurrencyCode.MXN);
    TransferResult cashResult =
        adapter.createTransfer(
            CreateTransferCommand.Cash.single(
                freshTransferId(),
                bankClearing,
                employerCollections,
                LedgerCodes.TransferCode.CASH_RECEIPT,
                remittance));
    assertThat(cashResult).isInstanceOf(TransferResult.Created.class);

    LedgerAccount.Cash bankClearingAccount =
        (LedgerAccount.Cash) adapter.lookupAccount(bankClearing).orElseThrow();
    LedgerAccount.Cash employerCollectionsAccount =
        (LedgerAccount.Cash) adapter.lookupAccount(employerCollections).orElseThrow();
    assertThat(bankClearingAccount.id()).isEqualTo(bankClearing);
    assertThat(bankClearingAccount.balance()).isEqualTo(remittance.negated());
    assertThat(employerCollectionsAccount.balance()).isEqualTo(remittance);

    long fundLedgerId = FUND_LEDGER_SEQ.incrementAndGet();
    AccountId unitsInIssue = freshAccountId();
    AccountId participantHoldings = freshAccountId();
    adapter.createAccount(
        new CreateAccountCommand(
            unitsInIssue, fundLedgerId, LedgerCodes.AccountCode.UNITS_IN_ISSUE));
    adapter.createAccount(
        new CreateAccountCommand(
            participantHoldings,
            fundLedgerId,
            LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED));

    Units allotment = Units.of(75_000_000_000L); // 750.00000000 units
    TransferResult unitResult =
        adapter.createTransfer(
            CreateTransferCommand.Unit.single(
                freshTransferId(),
                unitsInIssue,
                participantHoldings,
                fundLedgerId,
                LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT,
                allotment));
    assertThat(unitResult).isInstanceOf(TransferResult.Created.class);

    LedgerAccount.Unit unitsInIssueAccount =
        (LedgerAccount.Unit) adapter.lookupAccount(unitsInIssue).orElseThrow();
    LedgerAccount.Unit participantHoldingsAccount =
        (LedgerAccount.Unit) adapter.lookupAccount(participantHoldings).orElseThrow();
    // Sigma participant units = units in issue (docs/ENGINEERING-RULES.md Section 2) - no negation,
    // both sides
    // report
    // the same non-negative magnitude despite UNITS_IN_ISSUE being a contra account.
    assertThat(unitsInIssueAccount.balance()).isEqualTo(allotment);
    assertThat(participantHoldingsAccount.balance()).isEqualTo(allotment);
  }

  @Test
  void batchOf100WhereNumber50IsInvalidAppliesTheValidOnesAndReportsFailureAtThatPosition() {
    AccountId debit = freshAccountId();
    AccountId credit = freshAccountId();
    createBankClearingPair(debit, credit);

    int invalidIndex = 49; // "number 50", 0-based
    Money amount = Money.of(100L, CurrencyCode.MXN);
    List<CreateTransferCommand> cmds = new ArrayList<>(100);
    for (int i = 0; i < 100; i++) {
      if (i == invalidIndex) {
        // Self-transfer: TigerBeetle rejects this regardless of balances (AccountsMustBeDifferent).
        cmds.add(
            CreateTransferCommand.Cash.single(
                freshTransferId(), debit, debit, LedgerCodes.TransferCode.CASH_RECEIPT, amount));
      } else {
        cmds.add(
            CreateTransferCommand.Cash.single(
                freshTransferId(), debit, credit, LedgerCodes.TransferCode.CASH_RECEIPT, amount));
      }
    }

    List<TransferResult> results = adapter.createTransfers(cmds);
    assertThat(results).hasSize(100);
    for (int i = 0; i < 100; i++) {
      if (i == invalidIndex) {
        assertThat(results.get(i)).isInstanceOf(TransferResult.Rejected.class);
        assertThat(((TransferResult.Rejected) results.get(i)).reason())
            .isEqualTo(LedgerRejectionReason.SELF_TRANSFER);
      } else {
        assertThat(results.get(i)).isInstanceOf(TransferResult.Created.class);
      }
    }

    LedgerAccount.Cash creditAccount =
        (LedgerAccount.Cash) adapter.lookupAccount(credit).orElseThrow();
    assertThat(creditAccount.balance()).isEqualTo(Money.of(99 * 100L, CurrencyCode.MXN));
  }

  @Test
  void zeroAndNegativeAmountsAreRejectedLocallyWithoutReachingTigerBeetle() {
    AccountId debit = freshAccountId();
    AccountId credit = freshAccountId();
    createBankClearingPair(debit, credit);

    TransferResult zero =
        adapter.createTransfer(
            CreateTransferCommand.Cash.single(
                freshTransferId(),
                debit,
                credit,
                LedgerCodes.TransferCode.CASH_RECEIPT,
                Money.of(0L, CurrencyCode.MXN)));
    TransferResult negative =
        adapter.createTransfer(
            CreateTransferCommand.Cash.single(
                freshTransferId(),
                debit,
                credit,
                LedgerCodes.TransferCode.CASH_RECEIPT,
                Money.of(-100L, CurrencyCode.MXN)));

    assertThat(((TransferResult.Rejected) zero).reason())
        .isEqualTo(LedgerRejectionReason.ZERO_OR_NEGATIVE_AMOUNT);
    assertThat(((TransferResult.Rejected) negative).reason())
        .isEqualTo(LedgerRejectionReason.ZERO_OR_NEGATIVE_AMOUNT);

    CreateTransferCommand goodLeg =
        CreateTransferCommand.Cash.single(
            freshTransferId(),
            debit,
            credit,
            LedgerCodes.TransferCode.CASH_RECEIPT,
            Money.of(100L, CurrencyCode.MXN));
    CreateTransferCommand badLeg =
        CreateTransferCommand.Cash.single(
            freshTransferId(),
            debit,
            credit,
            LedgerCodes.TransferCode.CASH_RECEIPT,
            Money.of(-1L, CurrencyCode.MXN));
    List<TransferResult> chainResults = adapter.createLinkedChain(List.of(goodLeg, badLeg));
    assertThat(((TransferResult.Rejected) chainResults.get(0)).reason())
        .isEqualTo(LedgerRejectionReason.LINKED_CHAIN_FAILED);
    assertThat(((TransferResult.Rejected) chainResults.get(1)).reason())
        .isEqualTo(LedgerRejectionReason.ZERO_OR_NEGATIVE_AMOUNT);

    LedgerAccount.Cash creditAccount =
        (LedgerAccount.Cash) adapter.lookupAccount(credit).orElseThrow();
    assertThat(creditAccount.balance()).isEqualTo(Money.of(0L, CurrencyCode.MXN));
  }

  @Test
  void accountIdsRoundTripExactlyThroughTigerBeetlesTwoLongIdEncoding() {
    // Deliberately asymmetric most/least bits: a most<->least swap in the adapter would flip this.
    AccountId id = AccountId.of(0x1111_2222_3333_4444L, 0x5555_6666_7777_8888L);
    adapter.createAccount(
        new CreateAccountCommand(
            id, LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.ROUNDING_RESIDUE));

    Optional<LedgerAccount> found = adapter.lookupAccount(id);
    assertThat(found).isPresent();
    assertThat(found.orElseThrow().id()).isEqualTo(id);
  }

  @Test
  void tenantRefIsWrittenToUserData64OnBothAccountsAndTransfers() throws Exception {
    // ADR-0005 (2026-08-28 amendment): the tenant an organization's movements and per-holder
    // accounts belong to travels in user_data_64. The LedgerPort read models deliberately do not
    // surface it (the CDC stream does — Task 5.4/5.5 read it there), so this is verified with a
    // raw TigerBeetle lookup.
    TenantRef acme = TenantRef.derive(UUID.randomUUID(), new OrganizationId(UUID.randomUUID()));

    AccountId bankClearing = freshAccountId();
    AccountId employerCollections = freshAccountId();
    adapter.createAccount(
        new CreateAccountCommand(
            bankClearing, LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.BANK_CLEARING));
    adapter.createAccount(
        new CreateAccountCommand(
                employerCollections,
                LedgerCodes.CASH_LEDGER_ID,
                LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS)
            .withTenant(acme));

    assertThat(rawAccountUserData64(bankClearing)).isZero(); // a pooled account carries none
    assertThat(rawAccountUserData64(employerCollections)).isEqualTo(acme.value());

    TransferId transferId = freshTransferId();
    adapter.createTransfer(
        CreateTransferCommand.Cash.single(
                transferId,
                bankClearing,
                employerCollections,
                LedgerCodes.TransferCode.CASH_RECEIPT,
                Money.of(1_000L, CurrencyCode.MXN))
            .withTenant(acme));

    assertThat(rawTransferUserData64(transferId)).isEqualTo(acme.value());
  }

  /**
   * Row 26p: {@code createAccounts} opens a list longer than one request holds ({@link
   * LedgerLimits#MAX_ACCOUNTS_PER_REQUEST}) in as many requests as it needs, with exactly {@code
   * createAccount}'s per-account semantics — flags, ledger and tenant written, an existing account
   * not an error, and a rerun of the whole list a no-op.
   */
  @Test
  void createAccountsSpansRequestsKeepsEveryFieldAndIsIdempotent() throws Exception {
    long fundLedger = FUND_LEDGER_SEQ.incrementAndGet();
    TenantRef acme = TenantRef.derive(UUID.randomUUID(), new OrganizationId(UUID.randomUUID()));
    int count = LedgerLimits.MAX_ACCOUNTS_PER_REQUEST + 3;
    List<CreateAccountCommand> cmds = new ArrayList<>(count);
    cmds.add(
        new CreateAccountCommand(
            freshAccountId(), fundLedger, LedgerCodes.AccountCode.UNITS_IN_ISSUE));
    for (int i = 1; i < count; i++) {
      cmds.add(
          new CreateAccountCommand(
                  freshAccountId(), fundLedger, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED)
              .withTenant(acme));
    }
    // One already exists before the call: not an error, and not re-created.
    adapter.createAccount(cmds.get(7));

    List<AccountId> ids = adapter.createAccounts(cmds);

    assertThat(ids).containsExactlyElementsOf(cmds.stream().map(CreateAccountCommand::id).toList());
    // The last account of the first request and the first of the second, both present.
    int boundary = LedgerLimits.MAX_ACCOUNTS_PER_REQUEST;
    List<AccountId> probe =
        List.of(
            cmds.get(0).id(),
            cmds.get(boundary - 1).id(),
            cmds.get(boundary).id(),
            cmds.get(count - 1).id());
    List<LedgerAccount> found = adapter.lookupAccounts(probe);
    assertThat(found).extracting(LedgerAccount::id).containsExactlyInAnyOrderElementsOf(probe);
    LedgerAccount holding =
        found.stream()
            .filter(a -> a.id().equals(cmds.get(boundary).id()))
            .findFirst()
            .orElseThrow();
    assertThat(holding.accountCode())
        .isEqualTo(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED);
    assertThat(holding.debitsMustNotExceedCredits()).isTrue();
    assertThat(((LedgerAccount.Unit) holding).fundLedgerId()).isEqualTo(fundLedger);
    assertThat(rawAccountUserData64(cmds.get(boundary).id())).isEqualTo(acme.value());
    assertThat(rawAccountUserData64(cmds.get(0).id())).isZero();

    // A rerun of the whole list re-opens nothing and refuses nothing.
    assertThat(adapter.createAccounts(cmds)).isEqualTo(ids);
  }

  /**
   * A refusal other than "already exists" is a caller bug and throws, as {@code createAccount}
   * does, naming the first refused account — not a later one. Not atomic: an account before it in
   * the list stays created, which is safe because the list is rerun whole.
   */
  @Test
  void createAccountsThrowsNamingTheFirstRefusedAccount() {
    AccountId valid = freshAccountId();
    AccountId zero = AccountId.of(0L, 0L); // TigerBeetle: IdMustNotBeZero
    AccountId intMax = AccountId.of(-1L, -1L); // TigerBeetle: IdMustNotBeIntMax
    List<CreateAccountCommand> cmds =
        List.of(
            new CreateAccountCommand(
                valid, LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.ROUNDING_RESIDUE),
            new CreateAccountCommand(
                zero, LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.ROUNDING_RESIDUE),
            new CreateAccountCommand(
                intMax, LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.ROUNDING_RESIDUE));

    assertThatThrownBy(() -> adapter.createAccounts(cmds))
        .isInstanceOf(LedgerAccountRejectedException.class)
        .hasMessageContaining(zero.toString())
        .hasMessageContaining("IdMustNotBeZero");
    assertThat(adapter.lookupAccount(valid)).isPresent();
  }

  private static long rawAccountUserData64(AccountId id) throws Exception {
    IdBatch ids = new IdBatch(1);
    ids.add(id.leastSignificantBits(), id.mostSignificantBits());
    AccountBatch found = client.lookupAccountsAsync(ids).get();
    assertThat(found.next()).isTrue();
    return found.getUserData64();
  }

  private static long rawTransferUserData64(TransferId id) throws Exception {
    IdBatch ids = new IdBatch(1);
    ids.add(id.leastSignificantBits(), id.mostSignificantBits());
    TransferBatch found = client.lookupTransfersAsync(ids).get();
    assertThat(found.next()).isTrue();
    return found.getUserData64();
  }

  @Test
  void latestTransferTimestampIsTheTimestampOfTheTransferJustCommitted() {
    AccountId debit = freshAccountId();
    AccountId credit = freshAccountId();
    createBankClearingPair(debit, credit);
    TransferId id = freshTransferId();
    assertThat(
            adapter.createTransfer(
                CreateTransferCommand.Cash.single(
                    id,
                    debit,
                    credit,
                    LedgerCodes.TransferCode.CASH_RECEIPT,
                    Money.of(1_00L, CurrencyCode.MXN))))
        .isInstanceOf(TransferResult.Created.class);
    java.time.Instant committed = adapter.lookupTransfer(id).orElseThrow().timestamp();

    // The fixture is shared and other tests may commit in between, so the head is at least this
    // transfer's timestamp, never earlier — exact to the nanosecond when nothing else ran.
    assertThat(adapter.latestTransferTimestamp()).isPresent();
    assertThat(adapter.latestTransferTimestamp().orElseThrow()).isAfterOrEqualTo(committed);
  }

  private static void createBankClearingPair(AccountId debit, AccountId credit) {
    adapter.createAccount(
        new CreateAccountCommand(
            debit, LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.BANK_CLEARING));
    adapter.createAccount(
        new CreateAccountCommand(
            credit, LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS));
  }

  private static AccountId freshAccountId() {
    UUID uuid = UUID.randomUUID();
    return AccountId.of(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
  }

  private static TransferId freshTransferId() {
    UUID uuid = UUID.randomUUID();
    return TransferId.of(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
  }
}

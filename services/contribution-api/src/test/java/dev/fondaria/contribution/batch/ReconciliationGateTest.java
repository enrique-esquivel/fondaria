package dev.fondaria.contribution.batch;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferIdDeriver;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The matching rules, and the reason every amount comes from the ledger.
 *
 * <p>Control flow only — nothing here asserts a balance. The balance assertions run against a real
 * TigerBeetle in {@code ContributionBatchIntegrationTest}. What this proves is which receipts the
 * gate decides fund a batch, which is a decision, not a sum.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ReconciliationGateTest {

  private static final LocalDate VALUE_DATE = LocalDate.of(2026, 8, 20);

  private final OrganizationId organization = new OrganizationId(UUID.randomUUID());
  private final UUID batchId = UUID.randomUUID();
  private final AccountIdDeriver accountIds = new AccountIdDeriver(UUID.randomUUID());
  private final InMemoryCashClaimRepository claims = new InMemoryCashClaimRepository();
  private final StubLedgerPort ledger = new StubLedgerPort();

  private AccountId employerCollections;
  private ReconciliationGate gate;
  private ListAppender<ILoggingEvent> logs;
  private Level originalGateLevel;

  @BeforeEach
  void setUp() {
    employerCollections =
        accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organization.scope());
    gate = new ReconciliationGate(claims, ledger, accountIds);
    logs = new ListAppender<>();
    logs.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    logs.start();
    originalGateLevel = gateLogger().getLevel();
    gateLogger().addAppender(logs);
    gateLogger().setLevel(Level.TRACE);
  }

  @AfterEach
  void tearDown() {
    gateLogger().detachAppender(logs);
    gateLogger().setLevel(originalGateLevel);
  }

  private static Logger gateLogger() {
    return (Logger) LoggerFactory.getLogger(ReconciliationGate.class);
  }

  private TransferId givenReceipt(long cents, int dayOffset) {
    TransferId transferId = TransferIdDeriver.toTransferId(UUID.randomUUID());
    claims.givenPostedReceipt(
        transferId, VALUE_DATE.plusDays(dayOffset), Instant.EPOCH.plusSeconds(dayOffset));
    ledger.givenCashReceipt(transferId, employerCollections, cents);
    return transferId;
  }

  @Test
  void rule_1_a_single_receipt_equal_to_the_declared_total_funds_the_batch() {
    TransferId receipt = givenReceipt(750_000L, 0);

    ReconciliationGate.Outcome outcome = gate.evaluate(organization, batchId, 750_000L);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            ReconciliationGate.Outcome.Matched.class,
            matched -> {
              assertThat(matched.receipts()).containsExactly(receipt);
              assertThat(matched.receivedCents()).isEqualTo(750_000L);
            });
  }

  @Test
  void rule_1_matches_by_amount_rather_than_by_arrival_order() {
    // The scenario prefix-matching cannot serve: an employer with two outstanding wires
    // and two files, the second file arriving first. Ordering by value date would offer
    // the 400,000 wire to a file declaring 350,000 and block it.
    givenReceipt(400_000L, 0);
    TransferId later = givenReceipt(350_000L, 1);

    ReconciliationGate.Outcome outcome = gate.evaluate(organization, batchId, 350_000L);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            ReconciliationGate.Outcome.Matched.class,
            matched -> assertThat(matched.receipts()).containsExactly(later));
  }

  @Test
  void rule_1_declines_when_two_receipts_both_equal_the_declared_total() {
    // Choosing between them would be choosing whose money this is. An ambiguous
    // automatic answer about money is worse than no answer.
    givenReceipt(500_000L, 0);
    givenReceipt(500_000L, 1);

    ReconciliationGate.Outcome outcome = gate.evaluate(organization, batchId, 500_000L);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            ReconciliationGate.Outcome.NotMatched.class,
            notMatched -> {
              assertThat(notMatched.declaredCents()).isEqualTo(500_000L);
              assertThat(notMatched.availableCents()).isEqualTo(1_000_000L);
              assertThat(notMatched.candidateCount()).isEqualTo(2);
            });
  }

  @Test
  void rule_2_several_wires_together_fund_one_file() {
    TransferId first = givenReceipt(400_000L, 0);
    TransferId second = givenReceipt(350_000L, 1);

    ReconciliationGate.Outcome outcome = gate.evaluate(organization, batchId, 750_000L);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            ReconciliationGate.Outcome.Matched.class,
            matched -> {
              assertThat(matched.receipts()).containsExactlyInAnyOrder(first, second);
              assertThat(matched.receivedCents()).isEqualTo(750_000L);
            });
  }

  @Test
  void no_subset_short_of_the_whole_set_is_ever_taken() {
    // 300,000 + 450,000 = 750,000, and 300,000 alone is not the declared total. A
    // subset-sum search would find {300000, 450000} here — and would also find two
    // different subsets in other shapes, which is why there is no such search.
    givenReceipt(300_000L, 0);
    givenReceipt(450_000L, 1);
    givenReceipt(100_000L, 2);

    ReconciliationGate.Outcome outcome = gate.evaluate(organization, batchId, 750_000L);

    assertThat(outcome).isInstanceOf(ReconciliationGate.Outcome.NotMatched.class);
  }

  @Test
  void a_batch_waiting_on_cash_that_never_arrives_logs_why_rather_than_staying_silent() {
    // Task 29.13: a batch stuck in PENDING_RECONCILIATION with no cash available produced
    // no log line at all, indistinguishable from a sweep that had stopped running. Every
    // plain "nothing to match yet" outcome must say so — exactly once, and without the
    // organization's balance (AGENTS.md §7: a balance is never logged).
    givenReceipt(300_000L, 0);

    gate.evaluate(organization, batchId, 750_000L);

    assertThat(logs.list)
        .filteredOn(event -> event.getFormattedMessage().contains("not reconciled this sweep"))
        .hasSize(1)
        .first()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
              assertThat(event.getFormattedMessage())
                  .contains(batchId.toString())
                  .contains("candidateReceipts=1")
                  .doesNotContain("300000")
                  .doesNotContain("750000");
            });
  }

  @Test
  void a_shortfall_of_one_cent_blocks_the_batch() {
    // "to the cent" is the requirement, and this is what it costs.
    givenReceipt(749_999L, 0);

    ReconciliationGate.Outcome outcome = gate.evaluate(organization, batchId, 750_000L);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            ReconciliationGate.Outcome.NotMatched.class,
            notMatched -> assertThat(notMatched.availableCents()).isEqualTo(749_999L));
  }

  @Test
  void an_overpayment_blocks_the_batch_rather_than_allotting_part_of_it() {
    givenReceipt(760_000L, 0);

    ReconciliationGate.Outcome outcome = gate.evaluate(organization, batchId, 750_000L);

    assertThat(outcome).isInstanceOf(ReconciliationGate.Outcome.NotMatched.class);
  }

  @Test
  void no_cash_at_all_blocks_the_batch() {
    ReconciliationGate.Outcome outcome = gate.evaluate(organization, batchId, 750_000L);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            ReconciliationGate.Outcome.NotMatched.class,
            notMatched -> {
              assertThat(notMatched.availableCents()).isZero();
              assertThat(notMatched.candidateCount()).isZero();
            });
  }

  @Test
  void a_receipt_the_ledger_does_not_hold_funds_nothing() {
    // The whole point of reading the ledger. A row saying POSTED whose movement is not
    // in the ledger is not money, however confident the row is.
    TransferId phantom = TransferIdDeriver.toTransferId(UUID.randomUUID());
    claims.givenPostedReceipt(phantom, VALUE_DATE, Instant.EPOCH);

    ReconciliationGate.Outcome outcome = gate.evaluate(organization, batchId, 750_000L);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            ReconciliationGate.Outcome.NotMatched.class,
            notMatched -> {
              assertThat(notMatched.availableCents()).isZero();
              assertThat(notMatched.candidateCount()).isZero();
            });
  }

  @Test
  void a_transfer_that_credited_another_organization_funds_nothing() {
    TransferId foreign = TransferIdDeriver.toTransferId(UUID.randomUUID());
    claims.givenPostedReceipt(foreign, VALUE_DATE, Instant.EPOCH);
    ledger.givenCashReceipt(
        foreign,
        accountIds.derive(
            LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, UUID.randomUUID().toString()),
        750_000L);

    ReconciliationGate.Outcome outcome = gate.evaluate(organization, batchId, 750_000L);

    assertThat(outcome).isInstanceOf(ReconciliationGate.Outcome.NotMatched.class);
  }

  @Test
  void a_transfer_that_is_not_a_cash_receipt_funds_nothing() {
    TransferId wrongCode = TransferIdDeriver.toTransferId(UUID.randomUUID());
    claims.givenPostedReceipt(wrongCode, VALUE_DATE, Instant.EPOCH);
    ledger.givenTransfer(
        wrongCode, employerCollections, 750_000L, LedgerCodes.TransferCode.CORRECTION);

    ReconciliationGate.Outcome outcome = gate.evaluate(organization, batchId, 750_000L);

    assertThat(outcome).isInstanceOf(ReconciliationGate.Outcome.NotMatched.class);
  }

  @Test
  void a_receipt_another_batch_has_claimed_is_not_a_candidate() {
    TransferId claimed = givenReceipt(750_000L, 0);
    claims.givenClaimedBy(claimed, UUID.randomUUID());

    ReconciliationGate.Outcome outcome = gate.evaluate(organization, batchId, 750_000L);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            ReconciliationGate.Outcome.NotMatched.class,
            notMatched -> assertThat(notMatched.candidateCount()).isZero());
  }

  @Test
  void rule_0_cash_an_operator_assigned_to_this_batch_funds_it() {
    // The tie-break path. Two identical wires make rules 1 and 2 decline, so a human
    // assigns one; the next sweep must honour that rather than ignoring it.
    TransferId chosen = givenReceipt(500_000L, 0);
    givenReceipt(500_000L, 1);
    assertThat(gate.evaluate(organization, batchId, 500_000L))
        .isInstanceOf(ReconciliationGate.Outcome.NotMatched.class);

    claims.givenClaimedBy(chosen, batchId);

    assertThat(gate.evaluate(organization, batchId, 500_000L))
        .isInstanceOfSatisfying(
            ReconciliationGate.Outcome.AlreadyFunded.class,
            funded -> {
              assertThat(funded.receipts()).containsExactly(chosen);
              assertThat(funded.receivedCents()).isEqualTo(500_000L);
            });
  }

  @Test
  void rule_0_is_what_stops_an_assignment_stranding_the_batch_it_was_meant_to_free() {
    // Without it, an assigned receipt would simply vanish from the unclaimed candidates
    // and the batch would block forever with its money already spoken for — strictly
    // worse than never having assigned it.
    TransferId only = givenReceipt(750_000L, 0);
    claims.givenClaimedBy(only, batchId);

    assertThat(claims.unclaimed(organization)).isEmpty();
    assertThat(gate.evaluate(organization, batchId, 750_000L))
        .isInstanceOf(ReconciliationGate.Outcome.AlreadyFunded.class);
  }

  @Test
  void rule_0_assigns_several_receipts_together_when_that_is_what_a_human_chose() {
    TransferId first = givenReceipt(400_000L, 0);
    TransferId second = givenReceipt(350_000L, 1);
    claims.givenClaimedBy(first, batchId);
    claims.givenClaimedBy(second, batchId);

    assertThat(gate.evaluate(organization, batchId, 750_000L))
        .isInstanceOfSatisfying(
            ReconciliationGate.Outcome.AlreadyFunded.class,
            funded -> assertThat(funded.receipts()).containsExactlyInAnyOrder(first, second));
  }

  @Test
  void rule_0_blocks_an_assignment_that_does_not_cover_the_declared_total() {
    TransferId partial = givenReceipt(400_000L, 0);
    claims.givenClaimedBy(partial, batchId);

    assertThat(gate.evaluate(organization, batchId, 750_000L))
        .isInstanceOfSatisfying(
            ReconciliationGate.Outcome.NotMatched.class,
            notMatched -> assertThat(notMatched.availableCents()).isEqualTo(400_000L));
  }

  @Test
  void a_partial_assignment_is_never_topped_up_from_unassigned_cash() {
    // The important half of the previous test. 400,000 assigned plus 350,000 lying around
    // equals the declared total, and the gate must NOT take the second one: mixing a
    // human's partial decision with automatic matching would consume a wire nobody chose.
    TransferId assigned = givenReceipt(400_000L, 0);
    TransferId untouched = givenReceipt(350_000L, 1);
    claims.givenClaimedBy(assigned, batchId);

    assertThat(gate.evaluate(organization, batchId, 750_000L))
        .isInstanceOf(ReconciliationGate.Outcome.NotMatched.class);
    assertThat(claims.unclaimed(organization))
        .extracting(UnclaimedReceipt::transferId)
        .containsExactly(untouched);
  }

  @Test
  void rule_0_does_not_count_an_assigned_receipt_the_ledger_cannot_confirm() {
    // An assignment is a human saying which wire; it is not a human asserting that money
    // moved. The ledger still decides that.
    TransferId phantom = TransferIdDeriver.toTransferId(UUID.randomUUID());
    claims.givenPostedReceipt(phantom, VALUE_DATE, Instant.EPOCH);
    claims.givenClaimedBy(phantom, batchId);

    assertThat(gate.evaluate(organization, batchId, 750_000L))
        .isInstanceOfSatisfying(
            ReconciliationGate.Outcome.NotMatched.class,
            notMatched -> assertThat(notMatched.availableCents()).isZero());
  }

  @Test
  void another_batchs_assignment_does_not_fund_this_one() {
    TransferId theirs = givenReceipt(750_000L, 0);
    claims.givenClaimedBy(theirs, UUID.randomUUID());

    assertThat(gate.evaluate(organization, batchId, 750_000L))
        .isInstanceOf(ReconciliationGate.Outcome.NotMatched.class);
  }

  @Test
  void the_gate_writes_nothing() {
    // It is a decision, not an action. Claiming happens in the same transaction as the
    // status change, above this class, so a crash cannot leave cash claimed by a batch
    // no status says is funded.
    TransferId receipt = givenReceipt(750_000L, 0);

    gate.evaluate(organization, batchId, 750_000L);

    assertThat(claims.unclaimed(organization))
        .extracting(UnclaimedReceipt::transferId)
        .containsExactly(receipt);
  }
}

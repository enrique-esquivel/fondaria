package dev.fondaria.projection.consume;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.ledger.LedgerEntryEvent;
import dev.fondaria.ledger.LedgerTransferEvent;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The real {@link ProjectionSink} (Task 5.4): writes {@code projection.transfer} and advances the
 * receiving consumer's own checkpoint row (ADR-0032) in <b>one</b> Postgres transaction, per
 * decoded change event. Replaces the Task 5.3 {@code NoOpProjectionSink}.
 *
 * <p><b>Idempotent on the transfer's identity.</b> The insert is {@code ON CONFLICT (tenant_id,
 * transfer_id, value_date) DO NOTHING} — the table's primary key (V20 made it three columns for the
 * two-level partitioning; {@code value_date} is deterministic for a transfer, so a redelivery lands
 * on the same key). Delivering the same event a hundred times produces one row.
 *
 * <p><b>One transaction, Redis nowhere.</b> The insert and the checkpoint upsert commit together or
 * not at all. Nothing else is enlisted — there is no cache write in this path (Task 5.6 was struck
 * in favour of valuation-api's own cache), so no out-of-transaction failure can roll back a
 * committed projection row. {@link LedgerEventListener} acknowledges the Kafka offset only after
 * this method returns, i.e. after the commit ("ack last"); a crash in between redelivers, and the
 * {@code ON CONFLICT} makes that a no-op.
 *
 * <p><b>Entry events.</b> {@link ParticipantUnitsProjection} (Task 5.5) maintains the sparse {@code
 * participant_units} holding from every unit-holding entry leg, inside this method's transaction;
 * the checkpoint advance for the entry commits alongside it, so the cursor reflects both topics'
 * progress. The fan-out publishes only participant-holding legs (ADR-0040); any other leg would
 * write no holding row but still move the cursor.
 *
 * <p><b>Not derivable from the CDC event yet:</b> {@code nav_version_id} (the allotment chain
 * carries no NAV version in its {@code user_data}) is left null; {@code value_date} equals {@code
 * booking_date} for v1 — O-4's documented default — since no CDC field carries an explicit earlier
 * economic date (V20's column comment describing one is aspirational; see BACKLOG.md).
 */
@Component
public class JdbcProjectionSink implements ProjectionSink {

  private static final Logger LOGGER = LoggerFactory.getLogger(JdbcProjectionSink.class);

  /** No external cause: {@code user_data_128} is TigerBeetle's "unset" zero. */
  private static final String NO_EXTERNAL_REFERENCE = "0";

  private static final String INSERT_TRANSFER =
      """
      INSERT INTO projection.transfer
        (transfer_id, tenant_id, ledger_timestamp, ledger_id, debit_account_id, credit_account_id,
         amount_scaled, code, booking_date, value_date, reference)
      VALUES
        (CAST(:transferId AS numeric), :tenantId, CAST(:ledgerTimestamp AS numeric), :ledgerId,
         CAST(:debitAccountId AS numeric), CAST(:creditAccountId AS numeric),
         :amountScaled, :code, :bookingDate, :valueDate, :reference)
      ON CONFLICT (tenant_id, transfer_id, value_date) DO NOTHING
      """;

  private final JdbcClient jdbc;
  private final ProjectionCheckpoint checkpoint;
  private final BusinessCalendar calendar;
  private final ParticipantUnitsProjection participantUnits;
  private final TraceCorrelationReader traceCorrelation;

  public JdbcProjectionSink(
      JdbcClient jdbc,
      ProjectionCheckpoint checkpoint,
      BusinessCalendar calendar,
      ParticipantUnitsProjection participantUnits,
      TraceCorrelationReader traceCorrelation) {
    this.jdbc = jdbc;
    this.checkpoint = checkpoint;
    this.calendar = calendar;
    this.participantUnits = participantUnits;
    this.traceCorrelation = traceCorrelation;
  }

  @Override
  @Transactional
  public void accept(LedgerTransferEvent event, ProjectionCheckpoint.ConsumerRef consumer) {
    insertTransfer(event);
    checkpoint.advanceTo(consumer, event.timestamp());
  }

  @Override
  @Transactional
  public void accept(LedgerEntryEvent event, ProjectionCheckpoint.ConsumerRef consumer) {
    // Task 5.5: maintain the sparse participant_units holding for a unit-holding leg, then advance
    // the cursor — both in this one transaction. project(...) is a no-op for any other leg.
    participantUnits.project(event);
    checkpoint.advanceTo(consumer, event.timestamp());
  }

  /**
   * ADR-0034 §2: the whole batch in <b>one</b> transaction — the same statements the per-event
   * methods run, but one {@code COMMIT}, which is what each message used to wait on.
   *
   * <ul>
   *   <li>Transfers in {@code transfer_id} order and entries in holding order (tenant, account,
   *       fund, then ledger timestamp), so two batches in flight take their row and advisory locks
   *       in the same order. A deadlock that slips through anyway (a {@code hashtext} collision
   *       between holdings) aborts one batch, which the listener retries as any transient failure.
   *   <li>Several legs of one holding in one batch go through {@link
   *       ParticipantUnitsProjection#project} one after another inside this transaction: its
   *       advisory lock is transaction-scoped and re-entrant, and its order-independence (ADR-0024)
   *       makes the result the same as projecting them in separate transactions.
   *   <li>Each consumer's checkpoint row is advanced once, to the highest timestamp the batch
   *       carried for it. The row is {@code GREATEST}-only (ADR-0032 §1), so this equals advancing
   *       it per message.
   * </ul>
   */
  @Override
  @Transactional
  public void acceptBatch(List<Delivery> deliveries) {
    List<LedgerTransferEvent> transfers = new ArrayList<>();
    List<LedgerEntryEvent> entries = new ArrayList<>();
    Map<ProjectionCheckpoint.ConsumerRef, BigInteger> highestByConsumer = new LinkedHashMap<>();
    for (Delivery delivery : deliveries) {
      switch (delivery) {
        case Delivery.Transfer transfer -> transfers.add(transfer.event());
        case Delivery.Entry entry -> entries.add(entry.event());
      }
      highestByConsumer.merge(
          delivery.consumer(), new BigInteger(delivery.ledgerTimestamp()), BigInteger::max);
    }

    transfers.sort(Comparator.comparing(event -> new BigInteger(event.transferId())));
    for (LedgerTransferEvent transfer : transfers) {
      insertTransfer(transfer);
    }

    entries.sort(HOLDING_ORDER);
    for (LedgerEntryEvent entry : entries) {
      participantUnits.project(entry);
    }

    highestByConsumer.forEach(
        (consumer, highest) -> checkpoint.advanceTo(consumer, highest.toString()));
  }

  /** Tenant, account, fund, then ledger timestamp: the advisory lock's key, in a total order. */
  private static final Comparator<LedgerEntryEvent> HOLDING_ORDER =
      Comparator.comparing(LedgerEntryEvent::accountUserData64)
          .thenComparing(event -> new BigInteger(event.accountId()))
          .thenComparingLong(LedgerEntryEvent::ledger)
          .thenComparing(event -> new BigInteger(event.timestamp()));

  private void insertTransfer(LedgerTransferEvent event) {
    // Task 11.2: continues the trace contribution-api started for this transfer, if one was
    // found — a no-op scope otherwise, so this is exactly as safe as the write below whether or
    // not the correlation entry exists.
    try (var linked = traceCorrelation.continueTrace(event.transferId(), "projection.transfer")) {
      LocalDate bookingDate =
          calendar.businessDateOf(LedgerTimestamps.toInstant(event.timestamp()));
      long amountScaled =
          switch (event) {
            case LedgerTransferEvent.Cash cash -> cash.amount().cents();
            case LedgerTransferEvent.Unit unit -> unit.amount().scaled();
          };

      int inserted =
          jdbc.sql(INSERT_TRANSFER)
              .param("transferId", event.transferId())
              .param("tenantId", event.userData64())
              .param("ledgerTimestamp", event.timestamp())
              .param("ledgerId", Math.toIntExact(event.ledger()))
              .param("debitAccountId", event.debitAccountId())
              .param("creditAccountId", event.creditAccountId())
              .param("amountScaled", amountScaled)
              .param("code", event.transferCode())
              .param("bookingDate", bookingDate)
              .param("valueDate", bookingDate)
              .param("reference", externalReferenceOf(event))
              .update();

      if (inserted == 0) {
        LOGGER.debug("transfer {} already projected; redelivery is a no-op", event.transferId());
      }
    }
  }

  private static String externalReferenceOf(LedgerTransferEvent event) {
    String userData128 = event.userData128();
    return NO_EXTERNAL_REFERENCE.equals(userData128) ? null : userData128;
  }
}

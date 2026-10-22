package dev.fondaria.contribution.batch;

import dev.fondaria.ledger.TransferId;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A posted cash receipt that no batch has claimed yet.
 *
 * <p><b>It carries no amount, deliberately.</b> {@code workflow.cash_receipt.amount_minor_units} is
 * what an operator asserted a wire was; how much actually moved is a question only the ledger
 * answers, so the gate reads it from {@code LedgerPort.lookupTransfer} for every candidate. Putting
 * an amount on this record would put a second, staler number within reach of code that must not use
 * one (ADR-0008 invariant 1, restated by ADR-0011).
 */
public record UnclaimedReceipt(TransferId transferId, LocalDate valueDate, Instant postedAt) {}

package dev.fondaria.ledger;

/**
 * Our own reasons a {@link CreateTransferCommand} is rejected. Every result the underlying ledger
 * implementation can produce for a rejected transfer must map to exactly one of these (Task 2.4,
 * the TigerBeetle adapter — the only implementation, per ADR-0008); a vendor result not yet mapped
 * falls back to {@link #UNKNOWN}, which the adapter must log at error level so a new, unmapped
 * result is never silently swallowed.
 */
public enum LedgerRejectionReason {
  INSUFFICIENT_FUNDS,
  INSUFFICIENT_UNITS,
  ACCOUNT_NOT_FOUND,
  CROSS_LEDGER,
  CROSS_CLUSTER,
  ZERO_OR_NEGATIVE_AMOUNT,
  SELF_TRANSFER,
  PENDING_TRANSFER_NOT_FOUND,
  PENDING_TRANSFER_ALREADY_POSTED,
  PENDING_TRANSFER_ALREADY_VOIDED,
  PENDING_TRANSFER_EXPIRED,
  LINKED_CHAIN_FAILED,
  UNKNOWN
}

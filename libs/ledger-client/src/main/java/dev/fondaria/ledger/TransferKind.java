package dev.fondaria.ledger;

/** The lifecycle stage a {@link CreateTransferCommand} participates in. */
public enum TransferKind {
  /** An ordinary, immediately posted transfer. */
  SINGLE,
  /** Reserves the amount without posting it; must later be posted or voided, or it times out. */
  PENDING,
  /** Posts a previously created {@link #PENDING} transfer, identified by its pending id. */
  POST_PENDING,
  /** Cancels a previously created {@link #PENDING} transfer, identified by its pending id. */
  VOID_PENDING
}

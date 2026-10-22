package dev.fondaria.eodjob.reconciliation;

/**
 * The independent ways the platform can answer "what does the book hold".
 *
 * <p>The whole point of the internal tie-out is that these are computed from different places by
 * different code paths. {@link #LEDGER_PARTICIPANT_UNITS} and {@link #LEDGER_UNITS_IN_ISSUE} both
 * come from TigerBeetle but from opposite ends of the same postings — the sum of what every
 * participant holds against the single contra account that issued it — so a chain that credited a
 * participant without debiting issuance breaks their equality. {@link #PROJECTION_UNITS} comes from
 * PostgreSQL by way of the whole CDC path, so it breaks when a message is lost, replayed wrongly or
 * written to the wrong row.
 *
 * <p>Stored as text in {@code workflow.reconciliation_break}, and constrained there: the database
 * refuses a name this enum does not have.
 */
public enum Derivation {

  /**
   * Σ of every participant holding account, vested and unvested, read through {@code LedgerPort}.
   */
  LEDGER_PARTICIPANT_UNITS,

  /**
   * The fund's {@code UNITS_IN_ISSUE} contra account. By construction (chart-of-accounts.md §5) it
   * equals the sum above, always — that identity is what makes a one-account read a whole-fund
   * control.
   */
  LEDGER_UNITS_IN_ISSUE,

  /** Σ of the latest {@code projection.participant_units} row per participant, per fund. */
  PROJECTION_UNITS,

  /** A cash account's balance read through {@code LedgerPort}. */
  LEDGER_CASH,

  /** The same account's credits minus debits over {@code projection.transfer}. */
  PROJECTION_CASH
}

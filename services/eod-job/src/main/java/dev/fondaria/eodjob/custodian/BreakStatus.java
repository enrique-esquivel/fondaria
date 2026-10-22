package dev.fondaria.eodjob.custodian;

/**
 * Where a {@link CustodianBreak} stands in its investigation. Persisted as text and constrained in
 * {@code workflow.custodian_break} ({@code V27}).
 *
 * <p>Unlike {@code workflow.reconciliation_break} (Task 7.2), this status is updated in place — the
 * task requires a break be "recorded, aged, assigned and investigated", which is a case an operator
 * works, not a finding re-raised fresh every run.
 */
public enum BreakStatus {
  OPEN,
  ASSIGNED,
  INVESTIGATING,
  RESOLVED,
  ESCALATED;

  public boolean isTerminal() {
    return this == RESOLVED;
  }
}

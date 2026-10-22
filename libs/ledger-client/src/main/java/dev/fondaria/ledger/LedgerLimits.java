package dev.fondaria.ledger;

/**
 * Sizes the ledger imposes on a single request, expressed once so that the two sides of a linked
 * chain — whoever decides how much work goes into one, and whoever posts it — cannot disagree.
 *
 * <p><b>This is a property of the ledger, not a tunable.</b> A linked chain is atomic precisely
 * because it is contained in one request, so its ceiling is that request's message body:
 * TigerBeetle carries a one-megabyte body and a transfer is 128 bytes, which is {@value
 * #MAX_LINKED_CHAIN_LENGTH} transfers exactly. Measured directly against TigerBeetle 0.17.4 while
 * Task 21.5 was being written ({@code TASK-LOG.md} step 40): 8,189 accepted in 69 ms, 8,190
 * rejected. Raising it is not a configuration change — there is nothing to configure.
 *
 * <p>It lives in {@code ledger-client} rather than in either caller because both need it and
 * neither owns it. {@code contribution-api} sizes a batch so that its allotment fits in one chain;
 * {@code allotment-job} refuses a chain that would exceed it. A copy of the number in each would be
 * two numbers that are only equal until someone edits one.
 *
 * <p>No vendor type appears here, and none should: the constant is a fact about the deployed ledger
 * that {@code libs/ledger-client}'s vendor-neutral contract is allowed to state, the same way
 * {@link LedgerCodes#CASH_LEDGER_ID} states a fact about the chart of accounts.
 */
public final class LedgerLimits {

  /**
   * The most transfers one {@link LedgerPort#createLinkedChain} call can carry. A chain longer than
   * this is not slow or degraded — it is refused outright, and the ledger reports it as
   * unavailability rather than as a rejection, because the request never forms.
   */
  public static final int MAX_LINKED_CHAIN_LENGTH = 8_189;

  /**
   * The most accounts one ledger request can open. A TigerBeetle account is 128 bytes, the same as
   * a transfer, so the same one-megabyte body bounds it at the same count. {@link
   * LedgerPort#createAccounts} splits a longer list into requests of at most this many — unlike a
   * linked chain, account creation is not atomic, so splitting it changes nothing a caller can
   * observe.
   */
  public static final int MAX_ACCOUNTS_PER_REQUEST = 8_189;

  private LedgerLimits() {}
}

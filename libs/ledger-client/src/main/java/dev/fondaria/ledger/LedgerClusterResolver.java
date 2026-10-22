package dev.fondaria.ledger;

/**
 * Resolves which TigerBeetle cluster a ledger id — {@link LedgerCodes#CASH_LEDGER_ID} or one fund's
 * unit ledger id — is deployed to (ADR-0005). Configuration, not code: {@link
 * ClusterAwareLedgerPort} and every {@link LedgerPort} adapter it wraps are indifferent to how many
 * clusters exist, so adding a second cluster means adding an entry to whatever backs this resolver
 * at wiring time, never a code change.
 */
public interface LedgerClusterResolver {

  ClusterId resolveCluster(long ledgerId);
}

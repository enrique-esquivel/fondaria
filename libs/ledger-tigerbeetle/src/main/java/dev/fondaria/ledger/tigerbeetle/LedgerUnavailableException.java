package dev.fondaria.ledger.tigerbeetle;

/**
 * The TigerBeetle call did not complete: it was interrupted, timed out, or the client reported an
 * execution failure. No ledger state can be assumed from this — the caller does not know whether
 * the operation reached the cluster.
 */
public final class LedgerUnavailableException extends RuntimeException {

  public LedgerUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}

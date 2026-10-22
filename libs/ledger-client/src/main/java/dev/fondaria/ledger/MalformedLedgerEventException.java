package dev.fondaria.ledger;

/**
 * A ledger event message ({@code ledger.transfers.v1} / {@code ledger.entries.v1}) that is not a
 * well-formed {@code v1} event — bad JSON, a missing field, a field of the wrong JSON type, a
 * non-integral or negative numeric field, an amount that does not fit a signed 64-bit range, an
 * unrecognised {@code eventType}/{@code direction}, or a ledger id that is neither the cash ledger
 * nor a unit ledger.
 *
 * <p>Distinct from {@link UnknownLedgerEventVersionException}: this is a message whose version the
 * decoder <i>does</i> claim to understand but whose content it cannot. Both are for the consumer to
 * route to its dead-letter topic; neither is ever swallowed with a best-effort parse.
 */
public class MalformedLedgerEventException extends RuntimeException {

  public MalformedLedgerEventException(String message) {
    super(message);
  }

  public MalformedLedgerEventException(String message, Throwable cause) {
    super(message, cause);
  }
}

package dev.fondaria.bridge.cdc;

import java.io.Serial;

/**
 * A {@code bridge.ledger} message that cannot be turned into ledger events: malformed JSON, a
 * missing or mistyped field, a non-integral amount, or an unrecognised change-event type.
 *
 * <p>This is a <b>poison</b> signal — redelivering the same bytes will fail the same way forever —
 * so the listener routes the message to {@code ledger.entries.dlq} and acknowledges it, rather than
 * nacking it into an endless redelivery loop. That is not "skipping a message you can't process"
 * (docs/ENGINEERING-RULES.md §"Data flow"): the bytes are preserved verbatim for a human, and the
 * CDC lag alert fires on the gap. A transient producer failure is the opposite case and never
 * reaches here.
 */
public final class CdcMessageException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public CdcMessageException(String message) {
    super(message);
  }

  public CdcMessageException(String message, Throwable cause) {
    super(message, cause);
  }
}

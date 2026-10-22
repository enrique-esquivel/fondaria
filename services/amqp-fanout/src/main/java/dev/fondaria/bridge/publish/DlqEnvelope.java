package dev.fondaria.bridge.publish;

/**
 * What the bridge writes to {@code ledger.entries.dlq} when a {@code bridge.ledger} message cannot
 * be parsed or transformed. Carries the original bytes verbatim so a human can see exactly what the
 * ledger emitted, plus enough context to find it and understand why it stopped here.
 *
 * @param schemaVersion the bridge's schema version
 * @param reason a stable, machine-readable category
 * @param error the specific failure message
 * @param receivedAt when the bridge received the message (ISO-8601)
 * @param originalExchange the AMQP exchange the message arrived on
 * @param originalRoutingKey the AMQP routing key the message arrived with
 * @param originalBody the message body, verbatim, as UTF-8 text
 */
public record DlqEnvelope(
    String schemaVersion,
    String reason,
    String error,
    String receivedAt,
    String originalExchange,
    String originalRoutingKey,
    String originalBody) {

  public static final String REASON_UNPARSEABLE = "cdc_message_unparseable";
}

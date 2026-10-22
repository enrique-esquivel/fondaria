package dev.fondaria.bridge.publish;

/**
 * The RabbitMQ exchanges the fan-out service produces to (ADR-0022).
 *
 * <ul>
 *   <li>{@code ledger.transfers.v1} — fanout exchange for transfers.
 *   <li>{@code ledger.entries.v1} — x-consistent-hash exchange for entries, routed by account_id.
 *   <li>{@code ledger.entries.dlq} — exchange / queue for poison change events.
 * </ul>
 */
public final class LedgerTopics {

  /** Fanout exchange for change event transfers, published to all bound queues. */
  public static final String TRANSFERS = "ledger.transfers.v1";

  /**
   * Consistent-hash exchange for entry legs, routed by {@code account_id} so all entries for a
   * given account hash to the same queue in order.
   */
  public static final String ENTRIES = "ledger.entries.v1";

  /** Exchange/queue for change events the service could not parse or transform. */
  public static final String ENTRIES_DLQ = "ledger.entries.dlq";

  /** The schema version, matching the {@code v1} suffix on exchange names. */
  public static final String SCHEMA_VERSION = "v1";

  private LedgerTopics() {}
}

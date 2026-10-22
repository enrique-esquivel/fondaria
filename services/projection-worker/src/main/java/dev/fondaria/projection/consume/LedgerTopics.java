package dev.fondaria.projection.consume;

/**
 * The RabbitMQ exchanges the projection reads, and the dead-letter queue it writes a poison record
 * to (ADR-0022).
 *
 * <ul>
 *   <li>{@code ledger.transfers.v1} — fanout exchange for transfers.
 *   <li>{@code ledger.entries.v1} — consistent-hash exchange for entries, routed by account_id.
 *   <li>{@code ledger.entries.dlq} — dead-letter queue where unparseable messages are preserved.
 * </ul>
 */
public final class LedgerTopics {

  public static final String TRANSFERS = "ledger.transfers.v1";
  public static final String ENTRIES = "ledger.entries.v1";
  public static final String DEAD_LETTER = "ledger.entries.dlq";

  /** Matches the {@code v1} suffix on the input exchange names. */
  public static final String SCHEMA_VERSION = "v1";

  private LedgerTopics() {}
}

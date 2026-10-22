package dev.fondaria.eodjob.signoff;

/**
 * The Redpanda topic {@code eod-job} produces the daily close event to, and its fixed properties.
 *
 * <p>A contract, not tuning — {@code contracts/events/close-signed-off-v1.schema.json} is the
 * human-readable half, this is the code half, and {@code CloseSignedOffEventConformanceTest} ties
 * them together. Declared as a {@code NewTopic} bean applied by {@code KafkaAdmin} at startup, the
 * same "no separate provisioning step to forget" principle {@code services/amqp-redpanda-bridge}'s
 * {@code LedgerTopics} follows.
 *
 * <p><b>One partition.</b> This is one message per business date — a handful a week — and Task
 * 7.5b's statement scheduler is a single active consumer that must see them in close order. More
 * partitions would buy nothing and would let two of them be processed out of order.
 *
 * <p><b>Ninety days' retention.</b> A close event may need replay to re-drive a month's statements,
 * and a monthly trigger on a thirty-day retention is one missed deploy window from being gone. The
 * ledger event topics keep thirty days because their replay comes from the ledger itself ({@code
 * ADR-0002}); this one has no such backstop.
 */
public final class CloseSignoffTopic {

  /** One message per signed-off business date, keyed by the ISO date string. */
  public static final String NAME = "close.signed-off.v1";

  public static final int PARTITIONS = 1;

  public static final long RETENTION_MS = 90L * 24 * 60 * 60 * 1000;

  /**
   * Matches the {@code v1} suffix on the topic name and the {@code schemaVersion} on every message.
   */
  public static final String SCHEMA_VERSION = "v1";

  private CloseSignoffTopic() {}
}

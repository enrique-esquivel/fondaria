package dev.fondaria.statementworker.schedule;

/**
 * {@code statement.jobs.v1} — this service's own topic, one message per participant with an open
 * position at a month-end close (Task 7.5b, {@code ADR-0022}). Declared as a {@code NewTopic} bean
 * (see {@code KafkaProduceConfiguration}), the same "no separate provisioning step" principle
 * {@code eod-job}'s {@code CloseSignoffTopic} follows.
 *
 * <p><b>Many partitions, unlike {@code close.signed-off.v1}.</b> Every job is independent — one
 * participant's render touches nothing another's does — so throughput comes from spreading jobs
 * across partitions and adding worker consumers, the mechanism Task 7.5b's own acceptance criterion
 * names ("using additional consumers rather than a faster single worker"). Ordering across
 * participants carries no meaning here, unlike the ledger's per-account ordering requirement.
 *
 * <p><b>Retention long enough to outlast a stalled run.</b> A worker fleet scaled down between
 * close cycles must still be able to catch up on a whole month's jobs when it scales back up.
 */
public final class StatementJobsTopic {

  public static final String NAME = "statement.jobs.v1";

  public static final String SCHEMA_VERSION = "v1";

  private StatementJobsTopic() {}
}

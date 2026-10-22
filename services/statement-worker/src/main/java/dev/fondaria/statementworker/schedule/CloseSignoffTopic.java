package dev.fondaria.statementworker.schedule;

/**
 * {@code eod-job}'s {@code close.signed-off.v1} (Task 7.5a) — the trigger this service consumes.
 * This service never creates the topic; {@code eod-job} does ({@code KafkaAdmin}, one partition).
 */
public final class CloseSignoffTopic {

  public static final String NAME = "close.signed-off.v1";

  private CloseSignoffTopic() {}
}

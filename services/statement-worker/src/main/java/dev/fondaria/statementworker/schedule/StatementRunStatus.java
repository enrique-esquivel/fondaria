package dev.fondaria.statementworker.schedule;

public enum StatementRunStatus {
  RUNNING,
  COMPLETED,
  FAILED;

  public static StatementRunStatus parse(String value) {
    return valueOf(value);
  }
}

package dev.fondaria.statementworker.render;

/** Where a rendered {@link ParticipantStatement} is written (Task 7.5b). */
public interface StatementObjectStore {

  void put(String objectKey, byte[] bytes);
}

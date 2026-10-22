package dev.fondaria.statementworker.snapshot;

import java.io.Serial;

/** The object store did not serve a snapshot object this pass needs to read. */
public final class SnapshotObjectUnavailableException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public SnapshotObjectUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}

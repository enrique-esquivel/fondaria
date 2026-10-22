package dev.fondaria.contribution.batch;

import java.io.Serial;

/**
 * The submission itself is not usable — as opposed to the file it references being wrong, which is
 * a {@link BatchRejection} recorded on the batch rather than a failed request.
 *
 * <p>The distinction matters to the caller: this is a 400 saying "fix the call", while a rejection
 * is a 202 followed by a status resource saying "fix the file".
 */
public class InvalidBatchSubmissionException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public InvalidBatchSubmissionException(String message) {
    super(message);
  }
}

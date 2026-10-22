package dev.fondaria.contribution.participant.identity.web;

import java.io.Serial;

/** A path segment on the identity endpoint is not a valid UUID. */
final class InvalidIdentityPathException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  InvalidIdentityPathException(String message) {
    super(message);
  }
}

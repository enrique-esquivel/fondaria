package dev.fondaria.common.security;

/**
 * Thrown when an unauthenticated caller attempts to access a protected platform endpoint, mapping
 * to an RFC 9457 HTTP 401 ProblemDetail.
 */
public class AuthenticationRequiredException extends RuntimeException {

  public AuthenticationRequiredException(String message) {
    super(message);
  }

  public AuthenticationRequiredException() {
    this("Authentication required: missing or invalid authorization header");
  }
}

package dev.fondaria.common.security;

import java.net.URI;
import java.util.Objects;

/**
 * Thrown when a request is denied due to population, organization, or four-eyes authorization
 * policy. Translates to HTTP 403 Forbidden RFC 9457 problem detail.
 */
public class AccessDeniedProblemException extends RuntimeException {

  private static final URI DEFAULT_TYPE = URI.create("urn:fondaria:forbidden");

  private final URI type;
  private final String detail;

  public AccessDeniedProblemException(String detail) {
    this(DEFAULT_TYPE, detail);
  }

  public AccessDeniedProblemException(URI type, String detail) {
    super(detail);
    this.type = Objects.requireNonNull(type, "type");
    this.detail = Objects.requireNonNull(detail, "detail");
  }

  public URI type() {
    return type;
  }

  public String detail() {
    return detail;
  }
}

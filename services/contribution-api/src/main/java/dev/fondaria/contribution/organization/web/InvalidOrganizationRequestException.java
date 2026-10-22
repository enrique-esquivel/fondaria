package dev.fondaria.contribution.organization.web;

import java.io.Serial;

/**
 * A request could not be turned into a domain command. The message is rendered to the caller, so it
 * is written to be safe to render: it names the field and the rule, never the value.
 *
 * <p>This type exists so that only <i>vetted</i> messages reach a response body. Any other
 * exception is reported generically, because a stray message from a driver, a parser or the JDK
 * could carry the very value that must not leave the process (AGENTS.md §7).
 */
public final class InvalidOrganizationRequestException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public InvalidOrganizationRequestException(String message) {
    super(message);
  }
}

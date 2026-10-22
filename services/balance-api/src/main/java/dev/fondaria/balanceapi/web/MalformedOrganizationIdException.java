package dev.fondaria.balanceapi.web;

import java.io.Serial;

/**
 * The {@code organizationId} path segment is not a UUID.
 *
 * <p><b>A distinct type rather than a bare {@link IllegalArgumentException}</b>, for the reason
 * {@link MalformedParticipantIdException} records: {@code ParticipantScope} and {@code
 * AccountIdDeriver} both raise that type for a blank {@code participant_internal_ref}, a
 * data-integrity fault in {@code master.participant}. Reported as a malformed UUID it would page
 * nobody and be closed as a bad request.
 *
 * <p>Carries no value: the offending text came from a URL and must not reach a problem detail or a
 * log (AGENTS.md §7).
 */
public final class MalformedOrganizationIdException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public MalformedOrganizationIdException(Throwable cause) {
    super("organizationId is not a valid UUID", cause);
  }
}

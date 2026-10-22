package dev.fondaria.balanceapi.web;

import java.io.Serial;

/**
 * The {@code participantId} path segment is not a UUID.
 *
 * <p><b>A distinct type rather than a bare {@link IllegalArgumentException}, so that only this maps
 * to {@code 400}.</b> The handler previously matched every {@code IllegalArgumentException} and
 * told the caller their UUID was malformed — but {@code ParticipantScope} and {@code
 * AccountIdDeriver.derive} both raise that type for a blank {@code participant_internal_ref}, which
 * is a data-integrity fault in {@code master.participant} (its column is {@code NOT NULL} but has
 * no non-blank {@code CHECK}). Reporting that as client error would page nobody and be closed as a
 * bad request.
 *
 * <p>Carries no value: the offending text came from a URL and must not reach a problem detail or a
 * log (AGENTS.md §7).
 */
public final class MalformedParticipantIdException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public MalformedParticipantIdException(Throwable cause) {
    super("participantId is not a valid UUID", cause);
  }
}

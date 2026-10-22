package dev.fondaria.contribution.participant.web;

/** A {@code GET .../participants} query that names zero or both filters at once (400). */
public final class InvalidParticipantLookupRequestException extends RuntimeException {

  public InvalidParticipantLookupRequestException(String message) {
    super(message);
  }
}

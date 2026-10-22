package dev.fondaria.common;

import java.util.Objects;
import java.util.UUID;

/**
 * The opaque identifier of a participant, as carried by external contracts (Task 6.2, {@code
 * contracts/openapi/balance-api.yaml}).
 *
 * <p><b>This exists so that {@code participant_internal_ref} never has to travel.</b> An internal
 * ref identifies a person exactly as an {@code RFC} does, and anything in a URL path reaches an
 * access log, a proxy log and a trace (AGENTS.md §7). So the internal ref stays inside {@code
 * master.participant} and its primary key, and this is what appears in a path, a response body and
 * a message key.
 *
 * <p><b>Randomly assigned, unlike {@link OrganizationId}, and for a considered reason.</b> An
 * organization id is {@code UUIDv5(namespace, taxId)} so that registering the same employer twice
 * yields the same id with no registry to consult — provisioning is idempotent because identity is a
 * pure function of who the employer legally is. A participant needs none of that: enrolment is
 * already idempotent through {@code (organization_id, participant_internal_ref)}, so this id
 * carries no idempotency weight at all. Deriving it anyway would cost something real — {@code
 * UUIDv5(namespace, organizationId + ":" + internalRef)} is computable by anyone holding the
 * namespace, which makes the id a confirmation oracle for whether a given payroll reference exists
 * within a given employer. Random closes that, and {@link FundId} is the existing precedent for an
 * assigned rather than derived identifier.
 *
 * <p>Assignment happens once, in the database, as {@code master.participant.participant_id}'s
 * column default ({@code V22__participant_id.sql}) — so there is exactly one place a participant id
 * comes into existence, and no application code can mint a second one for someone who already has
 * one. It is never reassigned: a URL naming it must keep resolving to the same person forever.
 *
 * <p><b>Not a ledger scope.</b> A participant's holding accounts are derived per {@code
 * (organizationId, participantInternalRef, fundId)} ({@code AllotmentService.participantScope}) and
 * that derivation is unchanged by this type. Resolving this id back to those two components is a
 * lookup, not a computation — which is exactly the property that keeps it opaque.
 */
public record ParticipantId(UUID value) {

  public ParticipantId {
    Objects.requireNonNull(value, "value must not be null");
  }

  public static ParticipantId of(UUID value) {
    return new ParticipantId(value);
  }

  public static ParticipantId parse(String value) {
    Objects.requireNonNull(value, "value must not be null");
    try {
      return new ParticipantId(UUID.fromString(value));
    } catch (IllegalArgumentException e) {
      // Never echoes the offending value: it arrives from a URL and reaching a log or a
      // problem detail with it is the leak this type exists to prevent.
      throw new IllegalArgumentException("participantId is not a valid UUID");
    }
  }

  @Override
  public String toString() {
    return value.toString();
  }
}

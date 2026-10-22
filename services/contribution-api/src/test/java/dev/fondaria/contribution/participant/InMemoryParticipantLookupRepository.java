package dev.fondaria.contribution.participant;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** In-memory {@link ParticipantLookupRepository}, so the lookup controller needs no database. */
public final class InMemoryParticipantLookupRepository implements ParticipantLookupRepository {

  private final List<Entry> entries = new ArrayList<>();

  public void add(OrganizationId organizationId, ParticipantLookup lookup) {
    entries.add(new Entry(organizationId, lookup));
  }

  @Override
  public Optional<ParticipantLookup> findByInternalRef(
      OrganizationId organizationId, String internalRef) {
    return entries.stream()
        .filter(e -> e.organizationId.equals(organizationId))
        .filter(e -> e.lookup.internalRef().equals(internalRef))
        .map(e -> e.lookup)
        .findFirst();
  }

  @Override
  public Optional<ParticipantLookup> findByPayrollRef(
      OrganizationId organizationId, String payrollRef) {
    return entries.stream()
        .filter(e -> e.organizationId.equals(organizationId))
        .filter(e -> e.lookup.payrollRef().equals(payrollRef))
        .map(e -> e.lookup)
        .findFirst();
  }

  @Override
  public Optional<ParticipantLookup> findByParticipantId(
      OrganizationId organizationId, ParticipantId participantId) {
    return entries.stream()
        .filter(e -> e.organizationId.equals(organizationId))
        .filter(e -> e.lookup.participantId().equals(participantId))
        .map(e -> e.lookup)
        .findFirst();
  }

  private record Entry(OrganizationId organizationId, ParticipantLookup lookup) {}
}

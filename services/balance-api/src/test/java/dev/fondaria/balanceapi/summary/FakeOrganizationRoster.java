package dev.fondaria.balanceapi.summary;

import dev.fondaria.common.OrganizationId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@link OrganizationRoster} holding whatever organizations and participants a test states.
 *
 * <p><b>Insertion-ordered, and returned unordered on purpose.</b> The real roster has no {@code
 * ORDER BY} — the reason is in {@link OrganizationRoster}'s javadoc — so a service that came to
 * depend on an order would be re-opening the hazard that removed it. Nothing here supplies one.
 */
public final class FakeOrganizationRoster implements OrganizationRoster {

  private final Map<OrganizationId, List<String>> participants = new LinkedHashMap<>();

  /** Registers an organization with no participants. */
  public FakeOrganizationRoster withOrganization(OrganizationId organizationId) {
    participants.computeIfAbsent(organizationId, id -> new ArrayList<>());
    return this;
  }

  public FakeOrganizationRoster with(OrganizationId organizationId, String... internalRefs) {
    List<String> refs = participants.computeIfAbsent(organizationId, id -> new ArrayList<>());
    for (String internalRef : internalRefs) {
      if (!refs.contains(internalRef)) {
        refs.add(internalRef);
      }
    }
    return this;
  }

  @Override
  public boolean isRegistered(OrganizationId organizationId) {
    return participants.containsKey(organizationId);
  }

  @Override
  public List<String> participantsOf(OrganizationId organizationId) {
    return List.copyOf(participants.getOrDefault(organizationId, List.of()));
  }
}

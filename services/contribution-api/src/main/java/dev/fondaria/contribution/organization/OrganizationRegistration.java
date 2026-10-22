package dev.fondaria.contribution.organization;

/**
 * The outcome of a registration: the organization as stored, and whether this call is what created
 * it.
 *
 * <p>{@code created} exists so the API can answer {@code 201} the first time and {@code 200}
 * afterwards. It is a report, not a rule — a caller that ignores it still converges on exactly one
 * organization and one set of ledger accounts, which is the property that matters.
 */
public record OrganizationRegistration(Organization organization, boolean created) {}

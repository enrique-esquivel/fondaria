package dev.fondaria.eodjob.custodian;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The account at the external provider one or more {@code CustodianStatement}s are reconciled
 * against ({@code ADR-0019}).
 *
 * <p>Effective-dated ({@code reference.provider_account}, {@code V26}): resolved as of a date, the
 * highest {@code (effectiveFrom, version)} not after it — see {@link
 * ProviderAccountDirectory#activeAsOf}.
 *
 * @param organizationId present iff {@link #scopeType} is {@link ProviderScopeType#ORGANIZATION};
 *     empty for {@link ProviderScopeType#ALL_PARTICIPANTS}
 */
public record ProviderAccount(
    UUID providerAccountId,
    FundId fundId,
    ProviderScopeType scopeType,
    Optional<OrganizationId> organizationId,
    String providerRef,
    LocalDate effectiveFrom,
    int version,
    Instant publishedAt) {

  public ProviderAccount {
    Objects.requireNonNull(providerAccountId, "providerAccountId must not be null");
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(scopeType, "scopeType must not be null");
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
    Objects.requireNonNull(publishedAt, "publishedAt must not be null");
    if (providerRef == null || providerRef.isBlank()) {
      throw new IllegalArgumentException("providerRef must not be blank");
    }
    boolean hasOrganization = organizationId.isPresent();
    boolean expectsOrganization = scopeType == ProviderScopeType.ORGANIZATION;
    if (hasOrganization != expectsOrganization) {
      throw new IllegalArgumentException(
          "organizationId must be present if and only if scopeType is ORGANIZATION");
    }
  }

  public static ProviderAccount allParticipants(
      UUID providerAccountId,
      FundId fundId,
      String providerRef,
      LocalDate effectiveFrom,
      int version,
      Instant publishedAt) {
    return new ProviderAccount(
        providerAccountId,
        fundId,
        ProviderScopeType.ALL_PARTICIPANTS,
        Optional.empty(),
        providerRef,
        effectiveFrom,
        version,
        publishedAt);
  }

  public static ProviderAccount organization(
      UUID providerAccountId,
      FundId fundId,
      OrganizationId organizationId,
      String providerRef,
      LocalDate effectiveFrom,
      int version,
      Instant publishedAt) {
    return new ProviderAccount(
        providerAccountId,
        fundId,
        ProviderScopeType.ORGANIZATION,
        Optional.of(organizationId),
        providerRef,
        effectiveFrom,
        version,
        publishedAt);
  }
}

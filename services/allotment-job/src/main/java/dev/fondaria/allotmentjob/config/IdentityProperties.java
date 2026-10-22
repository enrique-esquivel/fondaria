package dev.fondaria.allotmentjob.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The namespace this service derives account and transfer ids under.
 *
 * <p>No {@code organizationNamespace} here, unlike contribution-api's record of the same name: this
 * service never registers an organization, only reads {@code OrganizationId} values already derived
 * by contribution-api off staged batches. It must resolve the identical {@code
 * EMPLOYER_COLLECTIONS} and unit-holding account ids contribution-api and valuation-api do, so
 * {@link #ledgerNamespace} must be the same value every one of those services is configured with.
 *
 * @param ledgerNamespace namespace for {@code AccountId} and {@code TransferId}
 */
@ConfigurationProperties(prefix = "fondaria.identity")
public record IdentityProperties(UUID ledgerNamespace) {

  public static final String LEDGER_NAMESPACE_VARIABLE = "LEDGER_ID_NAMESPACE";

  private static final String REMEDY =
      "Set it to a UUID that must never change once any ledger account exists, and that must "
          + "equal every other service's LEDGER_ID_NAMESPACE. See "
          + "infrastructure/compose/.env.example for the local-dev value.";

  /** Fails startup naming the absent variable, rather than silently deriving under a null. */
  public void requireComplete() {
    if (ledgerNamespace == null) {
      throw new ConfigurationException(
          "Required environment variable " + LEDGER_NAMESPACE_VARIABLE + " is not set", REMEDY);
    }
  }
}

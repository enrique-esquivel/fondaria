package dev.fondaria.contribution.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The two derivation namespaces this service derives stable identifiers under.
 *
 * <p><b>Both must be stable for the life of the platform.</b> The organization namespace is what
 * ties an employer to the id under which its rows and its ledger accounts already exist; the ledger
 * namespace is what ties an account role and scope to the TigerBeetle account already holding
 * money. Changing either re-derives every id and strands every existing balance under ids nothing
 * will look up again — which is why they are configuration with no default rather than constants
 * someone could edit in a diff without noticing what it costs.
 *
 * @param organizationNamespace namespace for {@code OrganizationId}
 * @param ledgerNamespace namespace for {@code AccountId} and {@code TransferId}
 */
@ConfigurationProperties(prefix = "fondaria.identity")
public record IdentityProperties(UUID organizationNamespace, UUID ledgerNamespace) {

  public static final String ORGANIZATION_NAMESPACE_VARIABLE = "ORGANIZATION_ID_NAMESPACE";
  public static final String LEDGER_NAMESPACE_VARIABLE = "LEDGER_ID_NAMESPACE";

  private static final String REMEDY =
      "Set it to a UUID that must never change once any organization is registered or any ledger "
          + "account exists. See infrastructure/compose/.env.example for the local-dev values.";

  /** Fails startup naming the absent variable, rather than silently deriving under a null. */
  public void requireComplete() {
    requirePresent(organizationNamespace, ORGANIZATION_NAMESPACE_VARIABLE);
    requirePresent(ledgerNamespace, LEDGER_NAMESPACE_VARIABLE);
  }

  private static void requirePresent(UUID value, String variable) {
    if (value == null) {
      throw new ConfigurationException(
          "Required environment variable " + variable + " is not set", REMEDY);
    }
  }
}

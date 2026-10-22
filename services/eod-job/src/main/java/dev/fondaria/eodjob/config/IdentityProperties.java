package dev.fondaria.eodjob.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The namespace this service derives account ids under.
 *
 * <p>No {@code organizationNamespace}, for the same reason allotment-job's record of the same name
 * has none: this service never registers an organization, it reads {@code OrganizationId} values
 * contribution-api already derived.
 *
 * <p><b>This is the setting a wrong reconciliation is most likely to come from.</b> The internal
 * tie-out re-derives every participant holding account and every pooled cash account from this
 * namespace, exactly as allotment-job derived them when it opened them. A namespace that differs by
 * one byte looks up accounts that do not exist, reads every one of them as holding nothing, and
 * reports the entire platform as drifted — a false break on every fund, which is the failure mode
 * an operator is least likely to read as a configuration error. It must equal every other service's
 * {@code LEDGER_ID_NAMESPACE}.
 *
 * @param ledgerNamespace namespace for {@code AccountId} derivation
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

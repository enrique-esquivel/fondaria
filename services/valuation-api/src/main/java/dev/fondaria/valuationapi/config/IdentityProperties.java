package dev.fondaria.valuationapi.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The derivation namespace this service derives ledger account ids under (Task 20.4).
 *
 * <p><b>Must be the identical value contribution-api is configured with.</b> Account ids are
 * derived deterministically from {@code (ledgerNamespace, accountCode, scope)} ({@code
 * AccountIdDeriver}), so a fund's {@code UNITS_IN_ISSUE} account only resolves to the same
 * TigerBeetle account contribution-api's allotments write to if both services agree on this
 * namespace. Unlike contribution-api's {@code IdentityProperties}, this service carries no
 * organization namespace — it has no notion of an organization.
 *
 * @param ledgerNamespace namespace for {@code AccountId}
 */
@ConfigurationProperties(prefix = "fondaria.identity")
public record IdentityProperties(UUID ledgerNamespace) {

  public static final String LEDGER_NAMESPACE_VARIABLE = "LEDGER_ID_NAMESPACE";

  private static final String REMEDY =
      "Set it to the same value configured for contribution-api's LEDGER_ID_NAMESPACE — account "
          + "ids are derived deterministically from it, and a mismatch would resolve a fund's units "
          + "in issue to a different TigerBeetle account than the one allotments actually write to. "
          + "See infrastructure/compose/.env.example.";

  /** Fails startup naming the absent variable, rather than silently deriving under a null. */
  public void requireComplete() {
    if (ledgerNamespace == null) {
      throw new ConfigurationException(
          "Required environment variable " + LEDGER_NAMESPACE_VARIABLE + " is not set", REMEDY);
    }
  }
}

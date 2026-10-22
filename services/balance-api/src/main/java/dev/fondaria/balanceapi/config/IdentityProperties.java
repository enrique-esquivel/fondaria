package dev.fondaria.balanceapi.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The derivation namespace this service resolves ledger account ids under.
 *
 * <p>Only the ledger namespace, and only for reading: this service derives a participant's {@code
 * PARTICIPANT_HOLDINGS_VESTED} / {@code _UNVESTED} account ids the same way {@code
 * AllotmentService} derived them when it opened those accounts, then looks their balances up. It
 * never creates an account and never posts a transfer.
 *
 * <p><b>It must be the identical value contribution-api and allotment-job use.</b> A different
 * namespace derives different account ids, and this service would report every participant as
 * holding nothing — a wrong balance that looks like a correct one, which is the worst shape a
 * failure can take here. Configuration with no default, for that reason.
 *
 * @param ledgerNamespace namespace for {@code AccountId}
 */
@ConfigurationProperties(prefix = "fondaria.identity")
public record IdentityProperties(UUID ledgerNamespace) {

  public static final String LEDGER_NAMESPACE_VARIABLE = "LEDGER_ID_NAMESPACE";

  private static final String REMEDY =
      "Set it to the same UUID contribution-api and allotment-job use. It must never change once "
          + "any ledger account exists. See infrastructure/compose/.env.example.";

  /** Fails startup naming the absent variable, rather than silently deriving under a null. */
  public void requireComplete() {
    if (ledgerNamespace == null) {
      throw new ConfigurationException(
          "Required environment variable " + LEDGER_NAMESPACE_VARIABLE + " is not set", REMEDY);
    }
  }
}

package dev.fondaria.contribution.organization;

import dev.fondaria.common.TaxIdScheme;
import dev.fondaria.common.TaxIdentifier;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates a well-formed {@code RFC} nobody else has used.
 *
 * <p>Fixtures are reused across local runs and TigerBeetle in particular cannot be reset (AGENTS.md
 * §4), so a test that registered a fixed tax identifier would, on its second run, assert against an
 * organization and a ledger account the previous run created. Every test here therefore registers
 * an employer that has never existed before and asserts only over what it created itself.
 *
 * <p>Public because the cash-receipt tests need the same guarantee: a receipt posts real money into
 * a real {@code EMPLOYER_COLLECTIONS} account, and reusing an employer between runs would have a
 * balance assertion pass or fail on what an earlier run left there.
 */
public final class FreshTaxIdentifiers {

  private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final String ALPHANUMERIC = LETTERS + "0123456789";

  private FreshTaxIdentifiers() {}

  /**
   * A persona-moral {@code RFC}: three name letters, {@code YYMMDD}, three homoclave characters.
   */
  public static String freshRfc() {
    StringBuilder rfc = new StringBuilder(12);
    for (int i = 0; i < 3; i++) {
      rfc.append(pick(LETTERS));
    }
    rfc.append("010101");
    for (int i = 0; i < 3; i++) {
      rfc.append(pick(ALPHANUMERIC));
    }
    return rfc.toString();
  }

  public static TaxIdentifier fresh() {
    return TaxIdentifier.of(TaxIdScheme.MX_RFC, freshRfc());
  }

  private static char pick(String alphabet) {
    return alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length()));
  }
}

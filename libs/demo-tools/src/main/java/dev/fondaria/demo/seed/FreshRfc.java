package dev.fondaria.demo.seed;

import java.util.Random;

/**
 * A well-formed persona-moral RFC, deterministic from {@code (seed, organizationIndex)} so a
 * `--seed` rerun registers the same organizations rather than a fresh 12 every time (`AGENTS.md`
 * §4's check-digit-not-enforced rule means any well-formed string is accepted).
 */
public final class FreshRfc {

  private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final String ALPHANUMERIC = LETTERS + "0123456789";

  private FreshRfc() {}

  public static String forOrganization(long seed, int organizationIndex) {
    Random random = new Random(seed * 1_000_033L + organizationIndex);
    StringBuilder rfc = new StringBuilder(12);
    for (int i = 0; i < 3; i++) {
      rfc.append(LETTERS.charAt(random.nextInt(LETTERS.length())));
    }
    rfc.append("010101");
    for (int i = 0; i < 3; i++) {
      rfc.append(ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length())));
    }
    return rfc.toString();
  }
}

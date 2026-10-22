package dev.fondaria.balanceapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The secret the movement-history cursor is signed with (Task 6.3).
 *
 * <p><b>No default.</b> A default would be in this repository, and a signing key in a repository
 * signs nothing — every deployment would accept cursors forged by anyone who has read the source
 * (docs/ENGINEERING-RULES.md §4: production must fail to start when a required secret is absent).
 * Local development sets it in {@code infrastructure/compose/.env.example} like every other value.
 *
 * <p><b>Long enough to be a key.</b> HMAC-SHA256 accepts any key length, so a two-character value
 * would be accepted silently and would be trivially brute-forced; the floor makes that a startup
 * failure naming the variable instead.
 *
 * <p>Rotating it invalidates every outstanding cursor. That surfaces as a {@code 400} on the next
 * page — a client starts its listing again — never as a wrong or a partial page.
 *
 * <p>Instances must share the value: a cursor issued by one and presented to another has to verify,
 * and they are behind the same address.
 *
 * @param cursorSecret the HMAC key, from the environment
 */
@ConfigurationProperties(prefix = "fondaria.query")
public record CursorProperties(String cursorSecret) {

  public static final String CURSOR_SECRET_VARIABLE = "QUERY_CURSOR_SECRET";

  /** Below this, the key is guessable rather than secret. */
  public static final int MINIMUM_LENGTH = 32;

  private static final String REMEDY =
      "Set it to a random string of at least "
          + MINIMUM_LENGTH
          + " characters, identical across every balance-api instance. Rotating it invalidates "
          + "outstanding pagination cursors, which clients recover from by starting a new page. "
          + "See infrastructure/compose/.env.example.";

  /** Fails startup naming the absent variable, rather than signing with nothing. */
  public void requireComplete() {
    if (cursorSecret == null || cursorSecret.isBlank()) {
      throw new ConfigurationException(
          "Required environment variable " + CURSOR_SECRET_VARIABLE + " is not set", REMEDY);
    }
    if (cursorSecret.length() < MINIMUM_LENGTH) {
      throw new ConfigurationException(
          "Environment variable "
              + CURSOR_SECRET_VARIABLE
              + " is shorter than "
              + MINIMUM_LENGTH
              + " characters",
          REMEDY);
    }
  }
}

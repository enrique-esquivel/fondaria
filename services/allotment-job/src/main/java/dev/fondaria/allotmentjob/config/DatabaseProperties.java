package dev.fondaria.allotmentjob.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PostgreSQL connection settings, bound from the environment.
 *
 * <p>Bound under {@code fondaria.database} rather than Spring's own {@code spring.datasource}:
 * binding is permissive here, so an absent variable becomes a {@link ConfigurationException} naming
 * it rather than a binder stack trace or a pool that fails on first use. Production must fail to
 * start when a required secret is absent (AGENTS.md §4) — there are no defaults below.
 *
 * @param url JDBC URL, e.g. {@code jdbc:postgresql://postgres:5432/funds}
 * @param username the login role; membership of {@code fondaria_app} is deployment configuration
 * @param password that role's password
 * @param connectTimeout how long a connection attempt may take before failing
 * @param maxPoolSize upper bound on pooled connections
 */
@ConfigurationProperties(prefix = "fondaria.database")
public record DatabaseProperties(
    String url, String username, String password, Duration connectTimeout, int maxPoolSize) {

  public static final String URL_VARIABLE = "POSTGRES_URL";
  public static final String USERNAME_VARIABLE = "POSTGRES_USER";
  public static final String PASSWORD_VARIABLE = "POSTGRES_PASSWORD";

  static final String REMEDY =
      "Set the PostgreSQL connection variables in the environment: "
          + URL_VARIABLE
          + " (a jdbc:postgresql:// URL), "
          + USERNAME_VARIABLE
          + " and "
          + PASSWORD_VARIABLE
          + ". See infrastructure/compose/.env.example.";

  public DatabaseProperties {
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
    maxPoolSize = maxPoolSize <= 0 ? 10 : maxPoolSize;
  }

  /**
   * Fails startup naming the absent variable. Never echoes a value: a JDBC URL carries a hostname
   * and the password is a secret (AGENTS.md §7).
   */
  public void requireComplete() {
    requirePresent(url, URL_VARIABLE);
    requirePresent(username, USERNAME_VARIABLE);
    requirePresent(password, PASSWORD_VARIABLE);
  }

  private static void requirePresent(String value, String variable) {
    if (value == null || value.isBlank()) {
      throw new ConfigurationException(
          "Required environment variable " + variable + " is not set", REMEDY);
    }
  }
}

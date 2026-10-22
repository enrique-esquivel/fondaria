package dev.fondaria.eodjob.config;

import java.io.Serial;

/**
 * A required piece of environment configuration is absent or unusable, so the service must not
 * start. {@link ConfigurationFailureAnalyzer} renders it as a named message rather than a stack
 * trace.
 *
 * <p>Carries the variable's name, never its value: an address value is a hostname, and hostnames
 * stay out of error output (AGENTS.md §7).
 */
public final class ConfigurationException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final String remedy;

  public ConfigurationException(String message, String remedy) {
    super(message);
    this.remedy = remedy;
  }

  public String remedy() {
    return remedy;
  }
}

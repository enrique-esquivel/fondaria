package dev.fondaria.contribution.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The master key (KEK) wrapping every participant's own data key (Task 9.5's envelope encryption).
 *
 * <p><b>No default here, on purpose (AGENTS.md §4).</b> A default master key would be a fixed value
 * anyone reading this repository could decrypt every participant's identity data with, in every
 * environment that forgot to override it — the opposite of what a secret is for. The local-dev
 * value lives in {@code infrastructure/compose/.env.example} only.
 *
 * @param masterKeyBase64 base64-encoded, exactly 32 bytes (AES-256) once decoded
 */
@ConfigurationProperties(prefix = "fondaria.participant-identity")
public record ParticipantIdentityProperties(String masterKeyBase64) {

  public static final String MASTER_KEY_VARIABLE = "PARTICIPANT_IDENTITY_MASTER_KEY";

  static final String REMEDY =
      "Set "
          + MASTER_KEY_VARIABLE
          + " to a base64-encoded 256-bit key, sourced from a Kubernetes Secret in every"
          + " environment but local development. See infrastructure/compose/.env.example for the"
          + " local-dev value. Changing this value crypto-shreds every participant whose wrapped"
          + " data key was wrapped under the previous one.";

  /** Fails startup naming the absent variable, rather than starting with no way to read a fact. */
  public void requireComplete() {
    if (masterKeyBase64 == null || masterKeyBase64.isBlank()) {
      throw new ConfigurationException(
          "Required environment variable " + MASTER_KEY_VARIABLE + " is not set", REMEDY);
    }
  }
}

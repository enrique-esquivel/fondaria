package dev.fondaria.valuationapi.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Object-storage settings for valuation files, bound from the environment (ADR-0009's transport,
 * reused by Task 20.3).
 *
 * <p>Absence is a startup failure naming the variable, per AGENTS.md §4: a service that started
 * without a bucket would accept upload-slot requests it cannot honour and leave every fund
 * unpriced. The local-development values live in {@code infrastructure/compose/.env.example}.
 *
 * @param bucket the bucket valuation files are uploaded to. A <b>separate bucket</b> from the
 *     contribution one: the two have different producers (an investment platform, not an employer),
 *     different retention and different access, and sharing one would mean a credential scoped to
 *     valuations could list employers' payroll files.
 * @param maximumObjectBytes largest file this service will read into memory. Far smaller than the
 *     contribution equivalent — a valuation file is one row per fund per date — but it still has to
 *     exist, because the size is chosen by the caller.
 */
@ConfigurationProperties(prefix = "fondaria.object-store")
public record ObjectStoreProperties(
    String endpoint,
    String accessKey,
    String secretKey,
    String region,
    String bucket,
    Duration uploadUrlTtl,
    long maximumObjectBytes,
    Duration callTimeout) {

  public static final String ENDPOINT_VARIABLE = "OBJECT_STORE_ENDPOINT";
  public static final String ACCESS_KEY_VARIABLE = "OBJECT_STORE_ACCESS_KEY";
  public static final String SECRET_KEY_VARIABLE = "OBJECT_STORE_SECRET_KEY";
  public static final String BUCKET_VARIABLE = "VALUATION_FILE_BUCKET";

  static final String REMEDY =
      "Set the object-store variables in the environment: "
          + ENDPOINT_VARIABLE
          + ", "
          + ACCESS_KEY_VARIABLE
          + ", "
          + SECRET_KEY_VARIABLE
          + " and "
          + BUCKET_VARIABLE
          + ". See infrastructure/compose/.env.example.";

  public ObjectStoreProperties {
    region = region == null || region.isBlank() ? "us-east-1" : region;
    uploadUrlTtl = uploadUrlTtl == null ? Duration.ofMinutes(15) : uploadUrlTtl;
    callTimeout = callTimeout == null ? Duration.ofSeconds(10) : callTimeout;
    maximumObjectBytes = maximumObjectBytes <= 0 ? 4L * 1024 * 1024 : maximumObjectBytes;
  }

  /** Fails startup naming the absent variable, and never echoes a value. */
  public void requireComplete() {
    requirePresent(endpoint, ENDPOINT_VARIABLE);
    requirePresent(accessKey, ACCESS_KEY_VARIABLE);
    requirePresent(secretKey, SECRET_KEY_VARIABLE);
    requirePresent(bucket, BUCKET_VARIABLE);
  }

  private static void requirePresent(String value, String variable) {
    if (value == null || value.isBlank()) {
      throw new ConfigurationException(
          "Required environment variable " + variable + " is not set", REMEDY);
    }
  }
}

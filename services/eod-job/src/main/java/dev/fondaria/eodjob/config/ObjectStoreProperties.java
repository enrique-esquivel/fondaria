package dev.fondaria.eodjob.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Object-storage settings for the daily snapshot (Task 7.4), bound from the environment.
 *
 * <p>Absence is a startup failure naming the variable, per docs/ENGINEERING-RULES.md §4: a close
 * that started without a bucket would derive a whole day's positions, tie them, and then discover
 * it had nowhere to put them — after the expensive part and before the useful one. The
 * local-development values live in {@code infrastructure/compose/.env.example}.
 *
 * <p>Deliberately a copy of contribution-api's and valuation-api's records of the same name rather
 * than a shared type: each service binds its own environment, and the three read different bucket
 * variables against the same endpoint. Sharing them would make one service's credentials the
 * other's.
 *
 * @param endpoint S3 API endpoint, e.g. {@code http://object-store:8333}. SeaweedFS is the pinned
 *     object store (ADR-0031) (docs/ENGINEERING-RULES.md §5); the endpoint is configuration so the
 *     same client speaks to whatever runs it.
 * @param region signing region. The object store ignores it, but SigV4 requires one to sign with,
 *     so it is a value rather than a guess.
 * @param bucket the bucket snapshots are written to. Provisioned by the deployment; this service
 *     never creates it and holds no credential that could.
 * @param callTimeout explicit per-call timeout (docs/ENGINEERING-RULES.md §4).
 */
@ConfigurationProperties(prefix = "fondaria.snapshot-object-store")
public record ObjectStoreProperties(
    String endpoint,
    String accessKey,
    String secretKey,
    String region,
    String bucket,
    Duration callTimeout) {

  public static final String ENDPOINT_VARIABLE = "OBJECT_STORE_ENDPOINT";
  public static final String ACCESS_KEY_VARIABLE = "OBJECT_STORE_ACCESS_KEY";
  public static final String SECRET_KEY_VARIABLE = "OBJECT_STORE_SECRET_KEY";
  public static final String BUCKET_VARIABLE = "SNAPSHOT_BUCKET";

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
    callTimeout = callTimeout == null ? Duration.ofSeconds(30) : callTimeout;
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

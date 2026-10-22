package dev.fondaria.contribution.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Object-storage settings for contribution files, bound from the environment (ADR-0009).
 *
 * <p>Absence is a startup failure naming the variable, per AGENTS.md §4: a service that started
 * without a bucket would accept upload-slot requests it cannot honour and reject every batch as
 * unreadable. The local-development values live in {@code infrastructure/compose/.env.example}.
 *
 * @param endpoint S3 API endpoint, e.g. {@code http://object-store:8333}. SeaweedFS is the pinned
 *     object store (ADR-0031) (AGENTS.md §5); the endpoint is configuration so the same client
 *     speaks to whatever runs it.
 * @param accessKey S3 access key
 * @param secretKey S3 secret key
 * @param region signing region. The object store ignores it, but SigV4 requires one to sign with,
 *     so it is a value rather than a guess.
 * @param bucket the bucket contribution files are uploaded to. Provisioned by the deployment; this
 *     service never creates it.
 * @param uploadUrlTtl how long a presigned upload slot stays valid. Short: a slot is a credential,
 *     and a long-lived one is a long-lived write grant.
 * @param maximumObjectBytes largest file this service will read into memory. A dispersion file can
 *     legitimately run to hundreds of thousands of rows, so it is generous — but unbounded is not
 *     an option when the object's size is chosen by the caller.
 * @param callTimeout explicit per-call timeout (AGENTS.md §4).
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
  public static final String BUCKET_VARIABLE = "CONTRIBUTION_FILE_BUCKET";

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
    maximumObjectBytes = maximumObjectBytes <= 0 ? 64L * 1024 * 1024 : maximumObjectBytes;
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

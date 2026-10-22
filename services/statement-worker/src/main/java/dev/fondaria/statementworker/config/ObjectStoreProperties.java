package dev.fondaria.statementworker.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Object-storage settings for the statement worker pool (Task 7.5b), bound from the environment.
 *
 * <p>One client, two buckets: the scheduler role <b>reads</b> Task 7.4's snapshot objects (the
 * platform's shared {@code SNAPSHOT_BUCKET}, the same variable {@code eod-job} writes to — this
 * service holds no credential that can write there) and the worker role <b>writes</b> the rendered
 * statement objects to a bucket of its own ({@code STATEMENT_BUCKET}), matching the separation
 * {@code eod-job}'s own {@code ObjectStoreProperties} javadoc argues for: a different producer, a
 * different retention, a different access pattern.
 *
 * <p>Absence is a startup failure naming the variable (AGENTS.md §4). Deliberately a copy of {@code
 * eod-job}'s record of a similar name rather than a shared type: each service binds its own
 * environment, and duplicating four fields is cheaper than a library coupling two services' startup
 * failures together.
 *
 * @param endpoint S3 API endpoint, e.g. {@code http://object-store:8333}. SeaweedFS is the pinned
 *     object store (ADR-0031) (AGENTS.md §5); the endpoint is configuration so the same client
 *     speaks to whatever runs it.
 * @param region signing region. The object store ignores it, but SigV4 requires one to sign with.
 * @param snapshotBucket the bucket Task 7.4's dense snapshots are read from. This service never
 *     writes here.
 * @param statementBucket the bucket rendered statements are written to. This service never reads an
 *     object here before writing it — every write is idempotent by content (AGENTS.md §4: rerun
 *     produces an identical hash), so there is nothing to check first.
 * @param callTimeout explicit per-call timeout (AGENTS.md §4).
 */
@ConfigurationProperties(prefix = "fondaria.statement-object-store")
public record ObjectStoreProperties(
    String endpoint,
    String accessKey,
    String secretKey,
    String region,
    String snapshotBucket,
    String statementBucket,
    Duration callTimeout) {

  public static final String ENDPOINT_VARIABLE = "OBJECT_STORE_ENDPOINT";
  public static final String ACCESS_KEY_VARIABLE = "OBJECT_STORE_ACCESS_KEY";
  public static final String SECRET_KEY_VARIABLE = "OBJECT_STORE_SECRET_KEY";
  public static final String SNAPSHOT_BUCKET_VARIABLE = "SNAPSHOT_BUCKET";
  public static final String STATEMENT_BUCKET_VARIABLE = "STATEMENT_BUCKET";

  static final String REMEDY =
      "Set the object-store variables in the environment: "
          + ENDPOINT_VARIABLE
          + ", "
          + ACCESS_KEY_VARIABLE
          + ", "
          + SECRET_KEY_VARIABLE
          + ", "
          + SNAPSHOT_BUCKET_VARIABLE
          + " and "
          + STATEMENT_BUCKET_VARIABLE
          + ". See infrastructure/compose/.env.example.";

  public ObjectStoreProperties {
    region = region == null || region.isBlank() ? "us-east-1" : region;
    // Five minutes, not thirty seconds. This one client also opens the snapshot object and holds
    // the stream while PositionFileReader gzip-decompresses and parses it — up to a million lines
    // (Task 7.4's own scale criterion) — and the AWS SDK v2 applies apiCallTimeout/
    // apiCallAttemptTimeout to the whole of a streaming call, not just the initial response. A
    // timeout sized for the quick statement-object PUT would fail a large snapshot read as a
    // (wrongly transient-looking) object-store outage before the parse ever finishes.
    callTimeout = callTimeout == null ? Duration.ofMinutes(5) : callTimeout;
  }

  /** Fails startup naming the absent variable, and never echoes a value. */
  public void requireComplete() {
    requirePresent(endpoint, ENDPOINT_VARIABLE);
    requirePresent(accessKey, ACCESS_KEY_VARIABLE);
    requirePresent(secretKey, SECRET_KEY_VARIABLE);
    requirePresent(snapshotBucket, SNAPSHOT_BUCKET_VARIABLE);
    requirePresent(statementBucket, STATEMENT_BUCKET_VARIABLE);
  }

  private static void requirePresent(String value, String variable) {
    if (value == null || value.isBlank()) {
      throw new ConfigurationException(
          "Required environment variable " + variable + " is not set", REMEDY);
    }
  }
}

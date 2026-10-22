package dev.fondaria.eodjob.snapshot;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * The one {@link SnapshotObjectStore} implementation, speaking the S3 API to the object store
 * (docs/ENGINEERING-RULES.md §5). The S3 API client is the single cloud-provider SDK
 * docs/ENGINEERING-RULES.md §4 permits, and only for this.
 *
 * <p>This class does not create the bucket, matching contribution-api's {@code S3ObjectStore}: the
 * bucket is provisioned by the deployment, so the close's credentials never need {@code
 * s3:CreateBucket}. A job that could delete and recreate the store holding every day's positions is
 * a far larger privilege than writing objects into it.
 *
 * <p>The failure of a {@code PUT} is deliberately not distinguished any further than "the store did
 * not accept it". Unlike a contribution file — where a missing object is the employer's problem and
 * an unreachable store is ours — every failure here is ours, and every one of them is retried by
 * simply running the pass again.
 */
public class S3SnapshotObjectStore implements SnapshotObjectStore {

  private final S3Client client;
  private final String bucket;
  private final S3Metrics metrics;

  public S3SnapshotObjectStore(S3Client client, String bucket, S3Metrics metrics) {
    this.client = client;
    this.bucket = bucket;
    this.metrics = metrics;
  }

  @Override
  public void put(String objectKey, byte[] bytes, String contentType) {
    long start = System.nanoTime();
    try {
      client.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(objectKey)
              .contentType(contentType)
              .contentLength((long) bytes.length)
              .build(),
          RequestBody.fromBytes(bytes));
      metrics.requestCompleted("put", System.nanoTime() - start, false);
    } catch (SdkException e) {
      metrics.requestCompleted("put", System.nanoTime() - start, true);
      // The key is safe to name — it is a date and a filename, never a participant
      // (docs/ENGINEERING-RULES.md §7).
      throw new ObjectStoreUnavailableException("could not store the object " + objectKey, e);
    }
  }
}

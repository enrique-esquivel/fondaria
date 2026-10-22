package dev.fondaria.statementworker.snapshot;

import dev.fondaria.statementworker.render.S3Metrics;
import java.io.InputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * The one {@link SnapshotObjectReader} implementation, speaking the S3 API to the object store
 * (docs/ENGINEERING-RULES.md §5). Read-only against {@code SNAPSHOT_BUCKET}: this service holds no
 * credential that can write there — Task 7.4's close is the only producer.
 */
public class S3SnapshotObjectReader implements SnapshotObjectReader {

  private final S3Client client;
  private final String bucket;
  private final S3Metrics metrics;

  public S3SnapshotObjectReader(S3Client client, String bucket, S3Metrics metrics) {
    this.client = client;
    this.bucket = bucket;
    this.metrics = metrics;
  }

  @Override
  public InputStream open(String objectKey) {
    long start = System.nanoTime();
    try {
      InputStream body =
          client.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
      metrics.requestCompleted("get", System.nanoTime() - start, false);
      return body;
    } catch (SdkException e) {
      metrics.requestCompleted("get", System.nanoTime() - start, true);
      // The key is safe to name — a date-prefixed filename, never a participant
      // (docs/ENGINEERING-RULES.md §7).
      throw new SnapshotObjectUnavailableException("could not read the object " + objectKey, e);
    }
  }
}

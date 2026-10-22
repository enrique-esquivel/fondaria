package dev.fondaria.statementworker.render;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * The one {@link StatementObjectStore} implementation, speaking the S3 API to the object store
 * (docs/ENGINEERING-RULES.md §5).
 *
 * <p>No conditional write, no read-before-write. Task 7.5b's own acceptance criterion — a rerun
 * produces an identical hash — is what makes a plain overwrite correct: the same {@link
 * ParticipantStatement} always renders to the same bytes, so writing them a second time (a
 * redelivered job message) is indistinguishable from not having written at all.
 */
public class S3StatementObjectStore implements StatementObjectStore {

  private final S3Client client;
  private final String bucket;
  private final S3Metrics metrics;

  public S3StatementObjectStore(S3Client client, String bucket, S3Metrics metrics) {
    this.client = client;
    this.bucket = bucket;
    this.metrics = metrics;
  }

  @Override
  public void put(String objectKey, byte[] bytes) {
    long start = System.nanoTime();
    try {
      client.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(objectKey)
              .contentType("application/json")
              .contentLength((long) bytes.length)
              .build(),
          RequestBody.fromBytes(bytes));
      metrics.requestCompleted("put", System.nanoTime() - start, false);
    } catch (SdkException e) {
      metrics.requestCompleted("put", System.nanoTime() - start, true);
      // The key is safe to name: period + the opaque participantId, never the internal ref
      // (docs/ENGINEERING-RULES.md §7).
      throw new StatementObjectUnavailableException("could not store the object " + objectKey, e);
    }
  }
}

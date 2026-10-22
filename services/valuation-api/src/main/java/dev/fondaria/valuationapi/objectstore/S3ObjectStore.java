package dev.fondaria.valuationapi.objectstore;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * The one {@link ObjectStore} implementation, speaking the S3 API to the object store
 * (docs/ENGINEERING-RULES.md §5). The S3 API client is the single cloud-provider SDK
 * docs/ENGINEERING-RULES.md §4 permits, and only for this.
 *
 * <p>This class does not create buckets. The bucket is provisioned by the deployment, so the
 * service's credentials never need {@code s3:CreateBucket}.
 *
 * <p>A missing object and an unreachable store are kept strictly apart. The first is the provider's
 * problem (nothing was uploaded under that key) and rejects the batch; the second is ours, and
 * leaves the batch to be retried. Collapsing them would reject a correct file during an outage and
 * leave a fund unpriced.
 */
public class S3ObjectStore implements ObjectStore {

  private final S3Client client;
  private final S3Presigner presigner;
  private final String bucket;
  private final Duration uploadUrlTtl;
  private final long maximumObjectBytes;
  private final Clock clock;
  private final S3Metrics metrics;

  public S3ObjectStore(
      S3Client client,
      S3Presigner presigner,
      String bucket,
      Duration uploadUrlTtl,
      long maximumObjectBytes,
      Clock clock,
      S3Metrics metrics) {
    this.client = client;
    this.presigner = presigner;
    this.bucket = bucket;
    this.uploadUrlTtl = uploadUrlTtl;
    this.maximumObjectBytes = maximumObjectBytes;
    this.clock = clock;
    this.metrics = metrics;
  }

  /**
   * The signature covers the bucket, the key and the method, so the slot cannot be redirected at
   * another object. It deliberately does not pin a content length or a checksum: the uploader
   * computes the SHA-256 it will submit over the bytes it actually sent, and this service verifies
   * that against the stored object itself ({@code valuation-file-v1.md} §4). Pinning it here would
   * move the check to the wrong side of the transport.
   */
  @Override
  public PresignedUpload presignUpload(String objectKey) {
    try {
      URI url =
          presigner
              .presignPutObject(
                  PutObjectPresignRequest.builder()
                      .signatureDuration(uploadUrlTtl)
                      .putObjectRequest(
                          PutObjectRequest.builder().bucket(bucket).key(objectKey).build())
                      .build())
              .url()
              .toURI();
      return new PresignedUpload(objectKey, url, clock.instant().plus(uploadUrlTtl));
    } catch (Exception e) {
      throw new ObjectStoreUnavailableException("Could not presign an upload slot", e);
    }
  }

  @Override
  public Optional<byte[]> fetch(String objectKey) {
    long size;
    long headStart = System.nanoTime();
    try {
      HeadObjectResponse head =
          client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
      size = head.contentLength() == null ? 0L : head.contentLength();
      metrics.requestCompleted("head", System.nanoTime() - headStart, false);
    } catch (NoSuchKeyException e) {
      metrics.requestCompleted("head", System.nanoTime() - headStart, false);
      return Optional.empty();
    } catch (S3Exception e) {
      // An S3 server can answer HEAD on a missing key with a bodyless 404, which the SDK cannot
      // always resolve to NoSuchKeyException — so the status is checked as well.
      if (e.statusCode() == 404) {
        metrics.requestCompleted("head", System.nanoTime() - headStart, false);
        return Optional.empty();
      }
      metrics.requestCompleted("head", System.nanoTime() - headStart, true);
      throw new ObjectStoreUnavailableException("Could not read object metadata", e);
    } catch (SdkException e) {
      metrics.requestCompleted("head", System.nanoTime() - headStart, true);
      throw new ObjectStoreUnavailableException("Could not read object metadata", e);
    }

    if (size > maximumObjectBytes) {
      throw new ObjectTooLargeException(size, maximumObjectBytes);
    }

    long getStart = System.nanoTime();
    byte[] bytes;
    try (InputStream body =
        client.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())) {
      // The read is timed as a single unit through the resource's own close (part of exiting
      // this try), not just the read call — a close failure is a failed outbound call too, and
      // recording success before it could still throw would record this one call twice over.
      bytes = body.readAllBytes();
    } catch (NoSuchKeyException e) {
      // Deleted between the HEAD and the GET. Absent is still absent.
      metrics.requestCompleted("get", System.nanoTime() - getStart, false);
      return Optional.empty();
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        metrics.requestCompleted("get", System.nanoTime() - getStart, false);
        return Optional.empty();
      }
      metrics.requestCompleted("get", System.nanoTime() - getStart, true);
      throw new ObjectStoreUnavailableException("Could not read object", e);
    } catch (SdkException | IOException e) {
      metrics.requestCompleted("get", System.nanoTime() - getStart, true);
      throw new ObjectStoreUnavailableException("Could not read object", e);
    }
    metrics.requestCompleted("get", System.nanoTime() - getStart, false);
    return Optional.of(bytes);
  }
}

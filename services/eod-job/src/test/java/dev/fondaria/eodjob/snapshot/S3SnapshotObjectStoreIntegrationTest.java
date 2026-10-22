package dev.fondaria.eodjob.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.fondaria.eodjob.support.ServiceIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

/**
 * The wire protocol, against a real object store. The decisions worth testing about the snapshot
 * itself belong to {@code SnapshotServiceTest}, which proves them without a container; this class
 * proves only that the bytes handed to the store come back out of it unchanged, and that a store
 * that refuses is reported as unavailable rather than as a snapshot that succeeded.
 */
@SpringBootTest
class S3SnapshotObjectStoreIntegrationTest extends ServiceIntegrationTest {

  @Autowired private SnapshotObjectStore objects;
  @Autowired private S3Client s3;
  @Autowired private MeterRegistry meterRegistry;

  @Test
  @DisplayName("stores gzipped NDJSON that decompresses to exactly what was written")
  void storesAndReadsBackTheIdenticalBytes() {
    String key = "snapshots/dt=2026-03-02/positions-" + UUID.randomUUID() + ".ndjson.gz";
    PositionFile file = onePositionFile();

    objects.put(key, file.gzippedBytes(), "application/x-ndjson");

    ResponseBytes<GetObjectResponse> stored =
        s3.getObjectAsBytes(GetObjectRequest.builder().bucket(SNAPSHOT_BUCKET).key(key).build());
    assertThat(stored.asByteArray()).isEqualTo(file.gzippedBytes());
    assertThat(gunzip(stored.asByteArray())).contains("\"unitsScaled\":10000000000").endsWith("\n");
    assertThat(
            s3.headObject(HeadObjectRequest.builder().bucket(SNAPSHOT_BUCKET).key(key).build())
                .contentType())
        .isEqualTo("application/x-ndjson");
    assertThat(
            meterRegistry
                .get("s3_request_duration_seconds")
                .tag("operation", "put")
                .timer()
                .count())
        .isPositive();
  }

  @Test
  @DisplayName("reports an unwritable bucket as unavailable, never as a stored snapshot")
  void anUnwritableBucketIsReportedAsUnavailable() {
    S3Metrics metrics = new S3Metrics(meterRegistry);
    SnapshotObjectStore missingBucket =
        new S3SnapshotObjectStore(s3, "no-such-bucket-" + UUID.randomUUID(), metrics);

    assertThatExceptionOfType(ObjectStoreUnavailableException.class)
        .isThrownBy(
            () ->
                missingBucket.put(
                    "snapshots/dt=2026-03-02/positions.ndjson.gz",
                    new byte[] {1, 2, 3},
                    "application/x-ndjson"))
        .withMessageContaining("could not store the object");
    assertThat(
            meterRegistry.get("s3_request_errors_total").tag("operation", "put").counter().count())
        .isPositive();
  }

  private static PositionFile onePositionFile() {
    try (PositionFileWriter writer = PositionFileWriter.open(1024L * 1024L)) {
      writer.write(
          SnapshotPosition.of(
              new dev.fondaria.common.OrganizationId(UUID.randomUUID()),
              "P-001",
              new dev.fondaria.common.FundId(UUID.randomUUID()),
              1001L,
              dev.fondaria.common.Units.of(100_00000000L),
              dev.fondaria.common.Nav.of(1_00000000L, dev.fondaria.common.CurrencyCode.MXN),
              7L,
              dev.fondaria.common.Money.of(9_000L, dev.fondaria.common.CurrencyCode.MXN)));
      return writer.finish();
    }
  }

  private static String gunzip(byte[] gzipped) {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}

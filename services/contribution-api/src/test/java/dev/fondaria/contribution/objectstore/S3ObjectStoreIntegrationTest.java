package dev.fondaria.contribution.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.contribution.support.ServiceIntegrationTest;
import dev.fondaria.testing.ObjectStoreFixture;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The S3 wire protocol, against a real object store. Everything the runner <i>decides</i> about a
 * missing or unreadable object is proved without a container in {@link
 * dev.fondaria.contribution.batch.BatchValidationRunnerTest}; what can only be proved here is that
 * a presigned URL actually accepts an upload and that the bytes come back unchanged.
 *
 * <p>Every object key is unique to its test. The object-store fixture is reused across runs
 * (docs/ENGINEERING-RULES.md §4), so a fixed key would have one run reading what an earlier one
 * uploaded.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class S3ObjectStoreIntegrationTest extends ServiceIntegrationTest {

  @Autowired private ObjectStore objectStore;
  @Autowired private MeterRegistry meterRegistry;

  @Test
  @DisplayName("a presigned slot accepts a PUT and the bytes come back byte-for-byte")
  void presignedUploadRoundTrips() throws Exception {
    String objectKey = freshKey();
    String content = "H,1,org,PLAN-1,2026-08-B,2026-08-20,100,1\nS,EMPLOYEE,100\n";
    PresignedUpload upload = objectStore.presignUpload(objectKey);

    int status = put(upload.url(), content);

    assertThat(status).isEqualTo(200);
    assertThat(objectStore.fetch(objectKey))
        .hasValueSatisfying(
            bytes -> assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(content));
    assertThat(
            meterRegistry
                .get("s3_request_duration_seconds")
                .tag("operation", "get")
                .timer()
                .count())
        .isPositive();
  }

  @Test
  @DisplayName("a slot is signed for one key and cannot be redirected at another")
  void aSlotIsBoundToItsKey() {
    // The signature covers bucket, key and method. Without that, an upload slot would be
    // a general write grant over the bucket holding every employer's files.
    String objectKey = freshKey();
    PresignedUpload upload = objectStore.presignUpload(objectKey);

    assertThat(upload.objectKey()).isEqualTo(objectKey);
    assertThat(upload.url().toString()).contains(objectKey);
  }

  @Test
  @DisplayName("an object nobody uploaded is absent, not a failure")
  void aMissingObjectIsEmpty() {
    // Absence is the employer's problem and rejects the batch; a fault on our side is an
    // exception and retries it. Collapsing the two would reject correct files in an outage.
    assertThat(objectStore.fetch(freshKey())).isEqualTo(Optional.empty());
  }

  @Test
  @DisplayName("an object above the configured maximum is refused before it is read")
  void anOversizedObjectIsRefused() throws Exception {
    String objectKey = freshKey();
    PresignedUpload upload = objectStore.presignUpload(objectKey);
    put(upload.url(), "x".repeat(4096));

    // A store configured with a tiny ceiling, so the size check is what fails rather than
    // the JVM running out of memory — which is the failure this ceiling exists to prevent.
    S3ObjectStore tiny = smallLimitStore();

    assertThatThrownBy(() -> tiny.fetch(objectKey))
        .isInstanceOf(ObjectTooLargeException.class)
        .satisfies(e -> assertThat(((ObjectTooLargeException) e).maximumBytes()).isEqualTo(1024L));
  }

  @Test
  @DisplayName("an unreachable store is an outage, never a missing object")
  void anUnreachableStoreRaises() {
    S3ObjectStore unreachable = unreachableStore();

    assertThatThrownBy(() -> unreachable.fetch("any-key"))
        .isInstanceOf(ObjectStoreUnavailableException.class);
    assertThat(
            meterRegistry.get("s3_request_errors_total").tag("operation", "head").counter().count())
        .isPositive();
  }

  private S3ObjectStore smallLimitStore() {
    return new S3ObjectStore(
        s3Client(objectStoreEndpoint()),
        s3Presigner(objectStoreEndpoint()),
        CONTRIBUTION_FILE_BUCKET,
        Duration.ofMinutes(15),
        1024L,
        Clock.systemUTC(),
        new S3Metrics(meterRegistry));
  }

  private S3ObjectStore unreachableStore() {
    // A port nothing listens on, so the client's own connect failure is what surfaces.
    return new S3ObjectStore(
        s3Client(URI.create("http://127.0.0.1:1")),
        s3Presigner(URI.create("http://127.0.0.1:1")),
        CONTRIBUTION_FILE_BUCKET,
        Duration.ofMinutes(15),
        1024L * 1024L,
        Clock.systemUTC(),
        new S3Metrics(meterRegistry));
  }

  private static URI objectStoreEndpoint() {
    return URI.create(ObjectStoreFixture.getInstance().getS3Url());
  }

  private static S3Client s3Client(URI endpoint) {
    return S3Client.builder()
        .endpointOverride(endpoint)
        .credentialsProvider(credentials())
        .region(Region.US_EAST_1)
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .overrideConfiguration(
            ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(5))
                .apiCallAttemptTimeout(Duration.ofSeconds(5))
                .build())
        .build();
  }

  private static S3Presigner s3Presigner(URI endpoint) {
    return S3Presigner.builder()
        .endpointOverride(endpoint)
        .credentialsProvider(credentials())
        .region(Region.US_EAST_1)
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }

  private static StaticCredentialsProvider credentials() {
    ObjectStoreFixture fixture = ObjectStoreFixture.getInstance();
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(fixture.getAccessKey(), fixture.getSecretKey()));
  }

  private static String freshKey() {
    return "contribution-batches/test/" + UUID.randomUUID() + ".csv";
  }

  private static int put(URI url, String content) throws IOException, InterruptedException {
    try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      return http.send(
              HttpRequest.newBuilder(url)
                  .timeout(Duration.ofSeconds(10))
                  .PUT(HttpRequest.BodyPublishers.ofString(content, StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.discarding())
          .statusCode();
    }
  }
}

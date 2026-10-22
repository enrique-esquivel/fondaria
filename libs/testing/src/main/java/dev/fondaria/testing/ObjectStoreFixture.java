package dev.fondaria.testing;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * Singleton fixture for the object store — SeaweedFS's S3 gateway (ADR-0031), started with the same
 * image and command as compose and Kubernetes. Starts once per JVM and provides connection details
 * and bucket reset capability.
 *
 * <p>A plain {@link GenericContainer}: Testcontainers has no SeaweedFS module, and none is needed.
 */
public final class ObjectStoreFixture {

  private static final Logger LOGGER = LoggerFactory.getLogger(ObjectStoreFixture.class);
  private static final int S3_PORT = 8333;
  private static final String ACCESS_KEY = "funds";
  private static final String SECRET_KEY = "funds_dev_pass";

  /**
   * Buckets older than this are left over from earlier runs of the reused container. Far longer
   * than any build, so a bucket a running test still uses is never old enough to go.
   */
  private static final Duration STALE_BUCKET_AGE = Duration.ofHours(6);

  private static volatile ObjectStoreFixture instance;

  private final GenericContainer<?> container;

  private ObjectStoreFixture() {
    ContainerImages.initDockerEnv();
    LOGGER.info("Starting object store container with image {}", ContainerImages.OBJECT_STORE);
    this.container =
        new GenericContainer<>(DockerImageName.parse(ContainerImages.OBJECT_STORE))
            // The image's entrypoint script chowns /data as root and drops to its own user;
            // calling weed directly keeps the command identical to compose and Kubernetes.
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/usr/bin/weed"))
            .withCommand(
                "server",
                "-dir=/data",
                "-s3",
                "-s3.port=" + S3_PORT,
                "-s3.port.iceberg=0",
                "-s3.port.lance=0",
                // A write to a missing bucket fails, as in compose and Kubernetes.
                "-s3.autoCreateBucket=false",
                "-master.volumeSizeLimitMB=1024",
                // A fixed slot count rather than free disk / volume size: volumes are sparse, and
                // test classes create a bucket (one collection, one volume) each (ADR-0031).
                "-volume.max=1000",
                // Master, volume and filer on loopback only, as in compose and Kubernetes.
                "-ip=127.0.0.1",
                "-ip.bind=127.0.0.1",
                "-s3.ip.bind=0.0.0.0")
            // One volume per new bucket, grown only as it fills (ADR-0031 amendment).
            .withEnv("WEED_MASTER_VOLUME_GROWTH_COPY_1", "1")
            // SeaweedFS's S3 gateway registers these as its one admin identity.
            .withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY)
            .withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY)
            .withExposedPorts(S3_PORT)
            .waitingFor(
                Wait.forHttp("/healthz")
                    .forPort(S3_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(60)))
            .withReuse(true)
            .withLogConsumer(new Slf4jLogConsumer(LOGGER));
    this.container.start();
    LOGGER.info("Object store container started at {}", getS3Url());
    pruneStaleBuckets();
  }

  public static ObjectStoreFixture getInstance() {
    if (instance == null) {
      synchronized (ObjectStoreFixture.class) {
        if (instance == null) {
          instance = new ObjectStoreFixture();
        }
      }
    }
    return instance;
  }

  public String getContainerId() {
    return container.getContainerId();
  }

  public String getHost() {
    return container.getHost();
  }

  public int getPort() {
    return container.getMappedPort(S3_PORT);
  }

  public String getAccessKey() {
    return ACCESS_KEY;
  }

  public String getSecretKey() {
    return SECRET_KEY;
  }

  public String getS3Url() {
    return "http://" + container.getHost() + ":" + getPort();
  }

  public S3Client createS3Client() {
    return S3Client.builder()
        .endpointOverride(URI.create(getS3Url()))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(getAccessKey(), getSecretKey())))
        .region(Region.US_EAST_1)
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }

  public synchronized void reset() {
    LOGGER.info("Resetting object store buckets...");
    try (S3Client s3Client = createS3Client()) {
      for (Bucket bucket : s3Client.listBuckets().buckets()) {
        deleteBucket(s3Client, bucket.name());
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to reset object store container", e);
    }
  }

  /**
   * Every bucket is its own SeaweedFS collection holding at least one volume, and the reused
   * container would otherwise keep every per-test-class bucket of every earlier run until its
   * volume slots ran out — a failure that depends on how many runs came before, not on the code
   * under test. Buckets older than {@link #STALE_BUCKET_AGE} can only be leftovers.
   */
  private void pruneStaleBuckets() {
    Instant cutoff = Instant.now().minus(STALE_BUCKET_AGE);
    try (S3Client s3Client = createS3Client()) {
      for (Bucket bucket : s3Client.listBuckets().buckets()) {
        if (bucket.creationDate() != null && bucket.creationDate().isBefore(cutoff)) {
          try {
            deleteBucket(s3Client, bucket.name());
          } catch (NoSuchBucketException e) {
            // Another JVM starting at the same moment pruned it first.
          }
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to prune stale object store buckets", e);
    }
  }

  private static void deleteBucket(S3Client s3Client, String bucketName) {
    s3Client
        .listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucketName).build())
        .contents()
        .forEach(
            obj ->
                s3Client.deleteObject(
                    DeleteObjectRequest.builder().bucket(bucketName).key(obj.key()).build()));
    s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build());
    LOGGER.info("Deleted object store bucket: {}", bucketName);
  }
}

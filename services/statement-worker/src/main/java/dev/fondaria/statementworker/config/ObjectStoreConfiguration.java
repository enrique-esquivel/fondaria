package dev.fondaria.statementworker.config;

import dev.fondaria.statementworker.render.S3Metrics;
import dev.fondaria.statementworker.render.S3StatementObjectStore;
import dev.fondaria.statementworker.render.StatementObjectStore;
import dev.fondaria.statementworker.snapshot.S3SnapshotObjectReader;
import dev.fondaria.statementworker.snapshot.SnapshotObjectReader;
import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * Wires the one S3 API client this service speaks to the object store with (Task 7.5b): the
 * scheduler reads Task 7.4's snapshot objects through it, and the workers write rendered statements
 * back through it. One client, two buckets — see {@link ObjectStoreProperties}.
 *
 * <p>This is the only place in {@code statement-worker} that names an S3 type; everything else
 * depends on {@link SnapshotObjectReader} or {@link StatementObjectStore}. Path-style access and
 * the synchronous URL-connection HTTP client, for the reasons {@code contribution-api}'s and {@code
 * eod-job}'s configurations of the same name record.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ObjectStoreProperties.class)
public class ObjectStoreConfiguration {

  @Bean(destroyMethod = "close")
  public S3Client statementS3Client(ObjectStoreProperties properties) {
    properties.requireComplete();
    return S3Client.builder()
        .endpointOverride(URI.create(properties.endpoint()))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
        .region(Region.of(properties.region()))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .overrideConfiguration(
            ClientOverrideConfiguration.builder()
                .apiCallTimeout(properties.callTimeout())
                .apiCallAttemptTimeout(properties.callTimeout())
                .build())
        .build();
  }

  @Bean
  public SnapshotObjectReader snapshotObjectReader(
      S3Client statementS3Client, ObjectStoreProperties properties, S3Metrics metrics) {
    return new S3SnapshotObjectReader(statementS3Client, properties.snapshotBucket(), metrics);
  }

  @Bean
  public StatementObjectStore statementObjectStore(
      S3Client statementS3Client, ObjectStoreProperties properties, S3Metrics metrics) {
    return new S3StatementObjectStore(statementS3Client, properties.statementBucket(), metrics);
  }
}

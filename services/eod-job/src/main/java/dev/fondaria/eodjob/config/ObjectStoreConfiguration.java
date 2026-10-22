package dev.fondaria.eodjob.config;

import dev.fondaria.eodjob.snapshot.S3Metrics;
import dev.fondaria.eodjob.snapshot.S3SnapshotObjectStore;
import dev.fondaria.eodjob.snapshot.SnapshotObjectStore;
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
 * Wires the S3 API client the daily snapshot is written through (Task 7.4).
 *
 * <p>This is the only place in {@code eod-job} that names an S3 type. Everything downstream depends
 * on {@link SnapshotObjectStore} alone, so the SDK cannot leak into the close's own code — the same
 * containment {@link LedgerConfiguration} gives TigerBeetle.
 *
 * <p><b>No presigner</b>, unlike contribution-api's configuration of the same name. Nothing uploads
 * a snapshot on the platform's behalf: the close writes the object itself, and the credential to
 * issue upload slots for a bucket holding every participant's positions is one this service has no
 * use for and therefore does not hold.
 *
 * <p>Path-style access, and the synchronous URL-connection HTTP client, for the reasons
 * contribution-api's pom and configuration record: virtual-hosted style needs wildcard DNS a
 * self-hosted object store does not have, and an async Netty transport would add a second
 * concurrency model to blocking code on virtual threads (AGENTS.md §4).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ObjectStoreProperties.class)
public class ObjectStoreConfiguration {

  @Bean(destroyMethod = "close")
  public S3Client snapshotS3Client(ObjectStoreProperties properties) {
    properties.requireComplete();
    return S3Client.builder()
        .endpointOverride(URI.create(properties.endpoint()))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
        .region(Region.of(properties.region()))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        // Explicit, per AGENTS.md §4. Generous compared with an API's: this writes a file that can
        // run to tens of megabytes, and a timeout tuned for a small object would fail the close on
        // a large book rather than on a real fault.
        .overrideConfiguration(
            ClientOverrideConfiguration.builder()
                .apiCallTimeout(properties.callTimeout())
                .apiCallAttemptTimeout(properties.callTimeout())
                .build())
        .build();
  }

  @Bean
  public SnapshotObjectStore snapshotObjectStore(
      S3Client snapshotS3Client, ObjectStoreProperties properties, S3Metrics metrics) {
    return new S3SnapshotObjectStore(snapshotS3Client, properties.bucket(), metrics);
  }
}

package dev.fondaria.valuationapi.config;

import dev.fondaria.valuationapi.objectstore.ObjectStore;
import dev.fondaria.valuationapi.objectstore.S3Metrics;
import dev.fondaria.valuationapi.objectstore.S3ObjectStore;
import java.net.URI;
import java.time.Clock;
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
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Wires the S3 API client for valuation-file storage.
 *
 * <p>This is the only place in {@code valuation-api} that names an S3 type. Everything downstream
 * depends on {@link ObjectStore} alone, so the SDK cannot leak into application code.
 *
 * <p><b>Path-style access, deliberately.</b> Virtual-hosted style puts the bucket in the hostname,
 * which needs wildcard DNS that a self-hosted object store does not have.
 *
 * <p>The synchronous URL-connection HTTP client is used rather than the SDK's defaults: this
 * service is blocking code on virtual threads (docs/ENGINEERING-RULES.md §4), so an async Netty
 * transport would add an event loop and a second concurrency model for no gain.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ObjectStoreProperties.class)
public class ObjectStoreConfiguration {

  private static StaticCredentialsProvider credentials(ObjectStoreProperties properties) {
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
  }

  @Bean(destroyMethod = "close")
  public S3Client s3Client(ObjectStoreProperties properties) {
    properties.requireComplete();
    return S3Client.builder()
        .endpointOverride(URI.create(properties.endpoint()))
        .credentialsProvider(credentials(properties))
        .region(Region.of(properties.region()))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        // Explicit, per docs/ENGINEERING-RULES.md §4. A read that hangs forever would hold a
        // validation
        // lease until it went stale, leaving the funds in that file unpriced for the whole
        // staleness window rather than failing and being retried on the next sweep.
        .overrideConfiguration(
            ClientOverrideConfiguration.builder()
                .apiCallTimeout(properties.callTimeout())
                .apiCallAttemptTimeout(properties.callTimeout())
                .build())
        .build();
  }

  /**
   * Presigning is a signing operation, not a network call, so this needs no HTTP client and cannot
   * fail because the object store is down — an upload slot can be issued during an object-store
   * outage, and the upload itself is the caller's to retry.
   */
  @Bean(destroyMethod = "close")
  public S3Presigner s3Presigner(ObjectStoreProperties properties) {
    properties.requireComplete();
    return S3Presigner.builder()
        .endpointOverride(URI.create(properties.endpoint()))
        .credentialsProvider(credentials(properties))
        .region(Region.of(properties.region()))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }

  @Bean
  public ObjectStore objectStore(
      S3Client s3Client,
      S3Presigner s3Presigner,
      ObjectStoreProperties properties,
      Clock clock,
      S3Metrics metrics) {
    return new S3ObjectStore(
        s3Client,
        s3Presigner,
        properties.bucket(),
        properties.uploadUrlTtl(),
        properties.maximumObjectBytes(),
        clock,
        metrics);
  }
}

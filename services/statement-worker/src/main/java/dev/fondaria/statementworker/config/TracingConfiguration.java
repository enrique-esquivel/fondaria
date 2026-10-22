package dev.fondaria.statementworker.config;

import dev.fondaria.common.tracing.TelemetryRedactionFilter;
import io.micrometer.observation.ObservationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Task 11.2: registers {@link TelemetryRedactionFilter} on this service's shared {@code
 * ObservationRegistry}. Spring Boot auto-detects every {@link ObservationFilter} bean and applies
 * it to every observation this service creates or that an auto-instrumented library creates on its
 * behalf — HTTP, JDBC, and the Kafka consumer/producer.
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfiguration {

  @Bean
  public ObservationFilter telemetryRedactionFilter() {
    return new TelemetryRedactionFilter();
  }
}

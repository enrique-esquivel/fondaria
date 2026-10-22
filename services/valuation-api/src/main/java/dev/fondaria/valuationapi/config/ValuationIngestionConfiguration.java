package dev.fondaria.valuationapi.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns on the scheduler the validator and the ageing alert run under, and supplies the {@link
 * TransactionTemplate} that makes recording valuations and marking a batch accepted one atomic
 * step.
 *
 * <p>Separate from the application class so a test that wants to drive a sweep itself can point the
 * poll interval far into the future and call the sweep directly rather than racing a timer.
 *
 * <p>The scheduler runs on virtual threads like everything else in this service ({@code
 * spring.threads.virtual.enabled}), so a sweep that blocks on the object store parks a virtual
 * thread rather than occupying a platform one.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ValuationIngestionProperties.class)
@EnableScheduling
public class ValuationIngestionConfiguration {

  @Bean
  public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
    return new TransactionTemplate(transactionManager);
  }
}

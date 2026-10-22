package dev.fondaria.contribution.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the scheduler the batch validator runs under (ADR-0009's asynchronous half).
 *
 * <p>Separate from the application class so the two can be reasoned about independently, and so a
 * test that wants to drive validation itself can point the poll interval far into the future and
 * call the sweep directly rather than racing a timer.
 *
 * <p>The scheduler runs on virtual threads like everything else in this service ({@code
 * spring.threads.virtual.enabled}), so a sweep that blocks on the object store or the ledger parks
 * a virtual thread rather than occupying a platform one.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BatchValidationProperties.class)
@EnableScheduling
public class BatchValidationConfiguration {}

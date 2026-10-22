package dev.fondaria.allotmentjob.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the scheduler the allotment run runs under.
 *
 * <p>Separate from the application class so a test that wants to drive allotment itself can point
 * the poll interval far into the future and call the sweep directly rather than racing a timer —
 * the same reason contribution-api's {@code BatchValidationConfiguration} is separate.
 *
 * <p>The scheduler runs on virtual threads like everything else in this service ({@code
 * spring.threads.virtual.enabled}), so a sweep that blocks on the ledger parks a virtual thread
 * rather than occupying a platform one.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AllotmentProperties.class)
@EnableScheduling
public class AllotmentConfiguration {}

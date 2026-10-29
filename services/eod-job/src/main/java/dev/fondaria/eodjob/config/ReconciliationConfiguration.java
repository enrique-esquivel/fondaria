package dev.fondaria.eodjob.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the scheduler the daily close runs under.
 *
 * <p>Separate from the application class so a test that wants to drive a close itself can point the
 * poll interval far into the future and call the pass directly rather than racing a timer — the
 * same reason allotment-job's {@code AllotmentConfiguration} is separate.
 *
 * <p>The scheduler runs on virtual threads like everything else in this service ({@code
 * spring.threads.virtual.enabled}), so a pass that blocks on the ledger for a few hundred
 * round-trips parks a virtual thread rather than occupying a platform one.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ReconciliationProperties.class, ProjectionCatchUpProperties.class})
@EnableScheduling
public class ReconciliationConfiguration {}

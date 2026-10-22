package dev.fondaria.eodjob.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the scheduler the dense-snapshot pass runs under, and binds its tuning. Separate from
 * {@link ReconciliationConfiguration} and {@link CustodianReconciliationConfiguration} for the
 * reason those are separate from each other: a test that drives one pass explicitly points the
 * others' poll intervals far into the future rather than racing their timers.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SnapshotProperties.class)
@EnableScheduling
public class SnapshotConfiguration {}

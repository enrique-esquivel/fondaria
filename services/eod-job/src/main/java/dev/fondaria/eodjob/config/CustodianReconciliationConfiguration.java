package dev.fondaria.eodjob.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the scheduler the custodian-reconciliation pass runs under. Separate from {@link
 * ReconciliationConfiguration} for the same reason that one is separate from the application class:
 * a test that wants to drive one pass without racing the other's timer points its own poll interval
 * far into the future.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CustodianReconciliationProperties.class)
@EnableScheduling
public class CustodianReconciliationConfiguration {}

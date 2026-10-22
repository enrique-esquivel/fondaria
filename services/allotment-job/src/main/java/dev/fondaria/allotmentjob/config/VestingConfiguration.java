package dev.fondaria.allotmentjob.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link VestingProperties}. Scheduling itself is already turned on by {@link
 * AllotmentConfiguration}'s {@code @EnableScheduling} — one scheduler for the whole service, so
 * {@code VestingRunner}'s own {@code @Scheduled} method needs no second {@code @EnableScheduling}
 * anywhere.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VestingProperties.class)
public class VestingConfiguration {}

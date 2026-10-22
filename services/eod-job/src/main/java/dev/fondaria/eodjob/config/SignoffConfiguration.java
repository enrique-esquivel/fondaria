package dev.fondaria.eodjob.config;

import org.springframework.amqp.core.FanoutExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the sign-off pass (Task 7.5a, ADR-0022): its schedule, its tuning, and the one RabbitMQ
 * exchange {@code eod-job} produces to.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SignoffProperties.class)
@EnableScheduling
public class SignoffConfiguration {

  public SignoffConfiguration(SignoffProperties properties) {
    properties.requireComplete();
  }

  @Bean
  public FanoutExchange closeSignedOffExchange(SignoffProperties properties) {
    return new FanoutExchange(properties.exchangeName(), true, false);
  }
}

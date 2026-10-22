package dev.fondaria.statementworker.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.DirectRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two roles' AMQP consumer wiring (Task 7.5b, ADR-0022). Both listener container factories
 * share {@code spring.rabbitmq.*}'s autoconfigured {@link ConnectionFactory} with {@link
 * AcknowledgeMode#MANUAL}: an acknowledgement is sent only after the work is durably done.
 *
 * <ul>
 *   <li><b>closeSignoffListenerContainerFactory</b>: concurrency = 1 (single active consumer).
 *   <li><b>statementJobListenerContainerFactory</b>: concurrency = {@code workerConcurrency}
 *       (spreads jobs across independent worker threads).
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StatementProperties.class)
public class AmqpConsumeConfiguration {

  @Bean
  public DirectRabbitListenerContainerFactory closeSignoffListenerContainerFactory(
      ConnectionFactory connectionFactory, StatementProperties properties) {
    properties.requireComplete();
    DirectRabbitListenerContainerFactory factory = new DirectRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    factory.setConsumersPerQueue(1);
    factory.setPrefetchCount(1);
    return factory;
  }

  @Bean
  public DirectRabbitListenerContainerFactory statementJobListenerContainerFactory(
      ConnectionFactory connectionFactory, StatementProperties properties) {
    DirectRabbitListenerContainerFactory factory = new DirectRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    factory.setConsumersPerQueue(properties.workerConcurrency());
    factory.setPrefetchCount(1);
    return factory;
  }
}

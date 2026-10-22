package dev.fondaria.statementworker.config;

import dev.fondaria.statementworker.render.DeadLetterPublisher;
import dev.fondaria.statementworker.schedule.CloseSignoffTopic;
import dev.fondaria.statementworker.schedule.StatementJobsTopic;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the AMQP topology for statement-worker (Task 7.5b, ADR-0022).
 *
 * <ul>
 *   <li>{@code close.signed-off.v1}: fanout exchange carrying the sign-off event from eod-job.
 *   <li>{@code statement.close-signoff.v1}: queue bound to the fanout exchange for the scheduler.
 *   <li>{@code statement.jobs.v1}: work queue carrying one job per participant.
 *   <li>{@code statement.jobs.dlq}: dead letter queue for poison jobs.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class AmqpTopologyConfiguration {

  public static final String CLOSE_SIGNOFF_QUEUE = "statement.close-signoff.v1";

  @Bean
  public FanoutExchange closeSignedOffExchange() {
    return new FanoutExchange(CloseSignoffTopic.NAME, true, false);
  }

  @Bean
  public Queue closeSignoffQueue() {
    return new Queue(CLOSE_SIGNOFF_QUEUE, true);
  }

  @Bean
  public Binding closeSignoffBinding(
      @Qualifier("closeSignoffQueue") Queue closeSignoffQueue,
      @Qualifier("closeSignedOffExchange") FanoutExchange closeSignedOffExchange) {
    return BindingBuilder.bind(closeSignoffQueue).to(closeSignedOffExchange);
  }

  @Bean
  public TopicExchange statementJobsExchange() {
    return new TopicExchange(StatementJobsTopic.NAME, true, false);
  }

  @Bean
  public Queue statementJobsQueue() {
    return new Queue(StatementJobsTopic.NAME, true);
  }

  @Bean
  public Binding statementJobsBinding(
      @Qualifier("statementJobsQueue") Queue statementJobsQueue,
      @Qualifier("statementJobsExchange") TopicExchange statementJobsExchange) {
    return BindingBuilder.bind(statementJobsQueue).to(statementJobsExchange).with("#");
  }

  @Bean
  public TopicExchange statementJobsDlqExchange() {
    return new TopicExchange(DeadLetterPublisher.TOPIC, true, false);
  }

  @Bean
  public Queue statementJobsDlqQueue() {
    return new Queue(DeadLetterPublisher.TOPIC, true);
  }

  @Bean
  public Binding statementJobsDlqBinding(
      @Qualifier("statementJobsDlqQueue") Queue statementJobsDlqQueue,
      @Qualifier("statementJobsDlqExchange") TopicExchange statementJobsDlqExchange) {
    return BindingBuilder.bind(statementJobsDlqQueue).to(statementJobsDlqExchange).with("#");
  }
}

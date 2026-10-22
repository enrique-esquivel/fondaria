package dev.fondaria.contribution.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns a {@link ConfigurationException} thrown during startup into a short, named message instead
 * of the stack trace Spring would otherwise print. Registered through {@code
 * META-INF/spring.factories}.
 *
 * <p>{@link AbstractFailureAnalyzer} walks the cause chain, so this still fires when the exception
 * is wrapped in a {@code BeanCreationException}.
 */
public class ConfigurationFailureAnalyzer extends AbstractFailureAnalyzer<ConfigurationException> {

  @Override
  protected FailureAnalysis analyze(Throwable rootFailure, ConfigurationException cause) {
    return new FailureAnalysis(cause.getMessage(), cause.remedy(), cause);
  }
}

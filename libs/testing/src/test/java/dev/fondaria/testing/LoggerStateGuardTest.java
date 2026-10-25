package dev.fondaria.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.ContextInitializer;
import ch.qos.logback.core.read.ListAppender;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import org.slf4j.LoggerFactory;

/**
 * Runs small sample classes through a nested JUnit engine — with extension auto-detection on, as
 * the root pom sets it — and checks that the guard fails the ones that leak, names the logger, and
 * puts the state back.
 *
 * <p>The samples are nested and only run through {@link EngineTestKit}: surefire skips nested
 * classes that are not {@code @Nested}, so they never run as tests of their own.
 */
class LoggerStateGuardTest {

  /** Each sample reads the logger name from here; a fresh one per run, so runs never interact. */
  private static String loggerName;

  @BeforeEach
  void freshLoggerName() {
    loggerName = "dev.fondaria.testing.guard-sample." + UUID.randomUUID();
  }

  @Test
  @DisplayName("a test that raises a level and does not restore it fails, and the level is reset")
  void leakedLevelFailsTheTest() {
    Events tests = run(LeaksALevel.class).testEvents();

    tests.assertStatistics(stats -> stats.started(1).failed(1));
    assertThat(failureMessage(tests)).contains(loggerName).contains("TRACE");
    assertThat(logger(loggerName).getLevel()).isNull();
  }

  @Test
  @DisplayName("a test that leaves an appender attached fails, and the appender is detached")
  void leakedAppenderFailsTheTest() {
    Events tests = run(LeaksAnAppender.class).testEvents();

    tests.assertStatistics(stats -> stats.started(1).failed(1));
    assertThat(failureMessage(tests)).contains(loggerName).contains("ListAppender");
    assertThat(logger(loggerName).iteratorForAppenders().hasNext()).isFalse();
  }

  @Test
  @DisplayName("a test that turns additivity off and does not restore it fails")
  void leakedAdditivityFailsTheTest() {
    Events tests = run(LeaksAdditivity.class).testEvents();

    tests.assertStatistics(stats -> stats.started(1).failed(1));
    assertThat(logger(loggerName).isAdditive()).isTrue();
  }

  @Test
  @DisplayName("a test that changes an existing level and puts it back passes")
  void restoredLevelPasses() {
    logger(loggerName).setLevel(Level.WARN);
    try {
      run(RestoresItsLevel.class).testEvents().assertStatistics(stats -> stats.succeeded(1));
      assertThat(logger(loggerName).getLevel()).isEqualTo(Level.WARN);
    } finally {
      logger(loggerName).setLevel(null);
    }
  }

  @Test
  @DisplayName("a test that only creates new loggers, as any class loading does, passes")
  void creatingLoggersPasses() {
    run(OnlyCreatesALogger.class).testEvents().assertStatistics(stats -> stats.succeeded(1));
  }

  @Test
  @DisplayName("a level leaked from @BeforeAll fails the class, and is reset")
  void leakedLevelFromBeforeAllFailsTheClass() {
    Events containers = run(LeaksFromBeforeAll.class).containerEvents();

    containers.assertStatistics(stats -> stats.failed(1));
    assertThat(logger(loggerName).getLevel()).isNull();
  }

  @Test
  @DisplayName("a test during which logback was reset is not compared, as after a Spring start")
  void resetDuringTheTestSkipsTheComparison() {
    run(ResetsLogback.class).testEvents().assertStatistics(stats -> stats.succeeded(1));
  }

  private static Logger logger(String name) {
    return (Logger) LoggerFactory.getLogger(name);
  }

  private static org.junit.platform.testkit.engine.EngineExecutionResults run(Class<?> sample) {
    return EngineTestKit.engine("junit-jupiter")
        .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
        .selectors(selectClass(sample))
        .execute();
  }

  private static String failureMessage(Events tests) {
    return tests.failed().stream()
        .findFirst()
        .flatMap(event -> event.getPayload(org.junit.platform.engine.TestExecutionResult.class))
        .flatMap(org.junit.platform.engine.TestExecutionResult::getThrowable)
        .map(Throwable::getMessage)
        .orElse("");
  }

  static class LeaksALevel {
    @Test
    void sample() {
      logger(loggerName).setLevel(Level.TRACE);
    }
  }

  static class LeaksAnAppender {
    @Test
    void sample() {
      ListAppender<ILoggingEvent> appender = new ListAppender<>();
      appender.start();
      logger(loggerName).addAppender(appender);
    }
  }

  static class LeaksAdditivity {
    @Test
    void sample() {
      logger(loggerName).setAdditive(false);
    }
  }

  static class RestoresItsLevel {
    private Level original;

    @BeforeEach
    void raise() {
      original = logger(loggerName).getLevel();
      logger(loggerName).setLevel(Level.TRACE);
    }

    @AfterEach
    void restore() {
      logger(loggerName).setLevel(original);
    }

    @Test
    void sample() {
      assertThat(logger(loggerName).getLevel()).isEqualTo(Level.TRACE);
    }
  }

  static class OnlyCreatesALogger {
    @Test
    void sample() {
      LoggerFactory.getLogger(loggerName + ".child").info("created");
    }
  }

  static class LeaksFromBeforeAll {
    @BeforeAll
    static void raise() {
      logger(loggerName).setLevel(Level.DEBUG);
    }

    @Test
    void sample() {}
  }

  static class ResetsLogback {
    @Test
    void sample() throws Exception {
      // What Spring Boot does when it (re)initialises logging: reset, then configure afresh — here
      // logback's own start-up configuration. The level set after it has no baseline to compare
      // against, so it is not flagged, by design.
      LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
      context.reset();
      new ContextInitializer(context).autoConfig();
      logger(loggerName).setLevel(Level.TRACE);
      logger(loggerName).setLevel(null);
    }
  }
}

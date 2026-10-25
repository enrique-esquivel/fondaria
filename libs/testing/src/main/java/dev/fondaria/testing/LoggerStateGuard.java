package dev.fondaria.testing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.core.Appender;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;

/**
 * Fails the test — or the class — that leaves a logback logger's level, additivity or appenders
 * different from how it found them, and puts them back so the next test starts clean.
 *
 * <p>Logback's configuration is JVM-wide. Spring Boot re-initialises it when it starts a new
 * context, so a leak only bites when a later class reuses a cached context, and then only in some
 * class orders: a TRACE root logger left behind captured a request body containing an RFC in a
 * later class, and a TRACE {@code dev.fondaria} broke another class's "DEBUG is suppressed"
 * assertion. Both passed in one order and failed in another. This guard makes the leaking test
 * fail, in every order.
 *
 * <p>Registered for every module that depends on this one through JUnit's extension auto-detection
 * ({@code META-INF/services}, enabled in the root pom's surefire configuration). Its {@code
 * beforeEach} runs before the test's own {@code @BeforeEach} methods and after Spring has loaded
 * the test's context; its {@code afterEach} runs after the test's own {@code @AfterEach} methods.
 * The class-level check covers {@code @BeforeAll}/{@code @AfterAll}.
 *
 * <p>When logback is reset during the guarded span — a test that builds a Spring context of its
 * own, or a class whose context is new — the comparison is skipped: Spring has legitimately
 * reconfigured every logger, and there is no baseline left to compare against.
 */
public final class LoggerStateGuard
    implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(LoggerStateGuard.class);

  @Override
  public void beforeAll(ExtensionContext context) {
    store(context).put(context.getUniqueId(), Snapshot.take(loggerContext()));
  }

  @Override
  public void afterAll(ExtensionContext context) {
    verify(context, "class");
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    store(context).put(context.getUniqueId(), Snapshot.take(loggerContext()));
  }

  @Override
  public void afterEach(ExtensionContext context) {
    verify(context, "test");
  }

  private static void verify(ExtensionContext context, String scope) {
    Snapshot before = store(context).remove(context.getUniqueId(), Snapshot.class);
    if (before == null) {
      return;
    }
    List<String> differences = before.restore(loggerContext());
    if (!differences.isEmpty()) {
      throw new AssertionError(
          "This "
              + scope
              + " changed JVM-wide logback state and did not restore it (restored now, so the next"
              + " test starts clean). Restore it in an @AfterEach/@AfterAll, in a finally block:\n  "
              + String.join("\n  ", differences));
    }
  }

  private static ExtensionContext.Store store(ExtensionContext context) {
    return context.getStore(NAMESPACE);
  }

  private static LoggerContext loggerContext() {
    return (LoggerContext) LoggerFactory.getILoggerFactory();
  }

  /** Every logger's level, additivity and appenders at one moment, and how to get back to it. */
  static final class Snapshot {

    private final ResetCounter resets;
    private final long resetsAtSnapshot;
    private final Map<String, LoggerState> loggers;

    private Snapshot(ResetCounter resets, Map<String, LoggerState> loggers) {
      this.resets = resets;
      this.resetsAtSnapshot = resets.count();
      this.loggers = loggers;
    }

    static Snapshot take(LoggerContext context) {
      ResetCounter resets = ResetCounter.installedOn(context);
      Map<String, LoggerState> loggers = new LinkedHashMap<>();
      for (Logger logger : context.getLoggerList()) {
        loggers.put(logger.getName(), LoggerState.of(logger));
      }
      return new Snapshot(resets, loggers);
    }

    /**
     * Puts every logger back as it was and describes what had changed; empty if nothing had. A
     * logger created since the snapshot is expected to be untouched: no level of its own, additive,
     * no appenders. Returns empty without touching anything if logback was reset in between.
     */
    List<String> restore(LoggerContext context) {
      if (resets.count() != resetsAtSnapshot || !resets.isInstalledOn(context)) {
        return List.of();
      }
      List<String> differences = new ArrayList<>();
      for (Logger logger : context.getLoggerList()) {
        LoggerState expected = loggers.getOrDefault(logger.getName(), LoggerState.UNTOUCHED);
        LoggerState actual = LoggerState.of(logger);
        if (!expected.equals(actual)) {
          differences.add(logger.getName() + ": was " + expected + ", left " + actual);
          expected.applyTo(logger);
        }
      }
      return differences;
    }
  }

  /** One logger's configuration. Appenders compare by identity: a new instance is a new one. */
  private record LoggerState(
      Level level, boolean additive, List<Appender<ILoggingEvent>> appenders) {

    static final LoggerState UNTOUCHED = new LoggerState(null, true, List.of());

    static LoggerState of(Logger logger) {
      List<Appender<ILoggingEvent>> appenders = new ArrayList<>();
      logger.iteratorForAppenders().forEachRemaining(appenders::add);
      return new LoggerState(logger.getLevel(), logger.isAdditive(), List.copyOf(appenders));
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof LoggerState that)) {
        return false;
      }
      if (!Objects.equals(level, that.level)
          || additive != that.additive
          || appenders.size() != that.appenders.size()) {
        return false;
      }
      for (int i = 0; i < appenders.size(); i++) {
        if (appenders.get(i) != that.appenders.get(i)) {
          return false;
        }
      }
      return true;
    }

    @Override
    public int hashCode() {
      return Objects.hash(level, additive, appenders.size());
    }

    void applyTo(Logger logger) {
      logger.setLevel(level);
      logger.setAdditive(additive);
      List<Appender<ILoggingEvent>> current = new ArrayList<>();
      logger.iteratorForAppenders().forEachRemaining(current::add);
      for (Appender<ILoggingEvent> appender : current) {
        if (appenders.stream().noneMatch(a -> a == appender)) {
          logger.detachAppender(appender);
        }
      }
      for (Appender<ILoggingEvent> appender : appenders) {
        if (current.stream().noneMatch(a -> a == appender)) {
          logger.addAppender(appender);
        }
      }
    }

    @Override
    public String toString() {
      return "{level="
          + (level == null ? "inherited" : level)
          + ", additive="
          + additive
          + ", appenders="
          + appenders.stream().map(a -> a.getClass().getSimpleName()).toList()
          + "}";
    }
  }

  /**
   * Counts logback resets, so a snapshot can tell that Spring reconfigured logging under it.
   * Reset-resistant, but {@link LoggerContext#stop()} drops every listener — so a counter that is
   * no longer installed counts as "reset happened" too.
   */
  private static final class ResetCounter implements LoggerContextListener {

    private final AtomicLong resets = new AtomicLong();

    static synchronized ResetCounter installedOn(LoggerContext context) {
      for (LoggerContextListener listener : context.getCopyOfListenerList()) {
        if (listener instanceof ResetCounter counter) {
          return counter;
        }
      }
      ResetCounter counter = new ResetCounter();
      context.addListener(counter);
      return counter;
    }

    long count() {
      return resets.get();
    }

    boolean isInstalledOn(LoggerContext context) {
      return context.getCopyOfListenerList().contains(this);
    }

    @Override
    public boolean isResetResistant() {
      return true;
    }

    @Override
    public void onStart(LoggerContext context) {}

    @Override
    public void onReset(LoggerContext context) {
      resets.incrementAndGet();
    }

    @Override
    public void onStop(LoggerContext context) {
      resets.incrementAndGet();
    }

    @Override
    public void onLevelChange(Logger logger, Level level) {}
  }
}

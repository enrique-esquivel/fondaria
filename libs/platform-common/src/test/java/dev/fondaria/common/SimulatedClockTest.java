package dev.fondaria.common;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SimulatedClock} against a lambda offset reader for the core offset/failure logic ({@link
 * SimulatedClock#wrappingForTest}), and against a fake JDBC stack for the production factory
 * ({@link SimulatedClock#wrapping}) — proving the SQL, the explicit timeout and the background poll
 * actually run, without a real database.
 */
class SimulatedClockTest {

  @Test
  @DisplayName("before the first poll, instant() is exactly the delegate's")
  void beforeFirstPollOffsetIsZero() {
    Clock delegate = Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC);
    SimulatedClock clock = SimulatedClock.wrappingForTest(delegate, () -> 0L);

    assertThat(clock.instant()).isEqualTo(delegate.instant());
  }

  @Test
  @DisplayName("instant() is the delegate plus the last polled offset")
  void offsetAppliesOnTopOfDelegate() {
    Clock delegate = Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC);
    long threeDaysInSeconds = java.time.Duration.ofDays(3).toSeconds();
    SimulatedClock clock = SimulatedClock.wrappingForTest(delegate, () -> threeDaysInSeconds);

    clock.poll();

    assertThat(clock.instant()).isEqualTo(delegate.instant().plusSeconds(threeDaysInSeconds));
    assertThat(clock.offsetSeconds()).isEqualTo(threeDaysInSeconds);
  }

  @Test
  @DisplayName("SimulatedClock never reads the system clock except through its delegate")
  void neverReadsSystemClockDirectly() {
    // A delegate fixed far from "now" proves instant() cannot be silently falling back to a raw
    // system clock anywhere in the offset arithmetic.
    Instant farFuture = Instant.parse("2099-01-01T00:00:00Z");
    Clock delegate = Clock.fixed(farFuture, ZoneOffset.UTC);
    SimulatedClock clock = SimulatedClock.wrappingForTest(delegate, () -> 60L);

    clock.poll();

    assertThat(clock.instant()).isEqualTo(farFuture.plusSeconds(60));
  }

  @Test
  @DisplayName("a poll failure keeps the last offset and logs exactly one WARN per failure episode")
  void failedPollKeepsLastOffsetAndLogsOncePerEpisode() {
    Clock delegate = Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC);
    AtomicInteger callCount = new AtomicInteger();
    SimulatedClock clock =
        SimulatedClock.wrappingForTest(
            delegate,
            () -> {
              int call = callCount.incrementAndGet();
              if (call == 1) {
                return 100L; // first poll succeeds
              }
              if (call <= 4) {
                throw new java.sql.SQLException("connection refused"); // three failures
              }
              return 200L; // recovers
            });

    Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(SimulatedClock.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      clock.poll(); // succeeds: offset = 100
      clock.poll(); // fails #1: logs WARN
      clock.poll(); // fails #2: no new WARN
      clock.poll(); // fails #3: no new WARN
      assertThat(clock.offsetSeconds()).isEqualTo(100L); // unchanged across the three failures

      long warnCount =
          appender.list.stream().filter(event -> event.getLevel() == Level.WARN).count();
      assertThat(warnCount).as("exactly one WARN across the failing episode").isEqualTo(1);

      clock.poll(); // recovers: offset = 200
      assertThat(clock.offsetSeconds()).isEqualTo(200L);
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  @DisplayName("a new failure episode after recovery logs a new WARN")
  void failureAfterRecoveryLogsAgain() {
    Clock delegate = Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC);
    AtomicInteger callCount = new AtomicInteger();
    SimulatedClock clock =
        SimulatedClock.wrappingForTest(
            delegate,
            () -> {
              int call = callCount.incrementAndGet();
              if (call == 2) {
                return 50L; // one successful poll between the two failures
              }
              throw new java.sql.SQLException("connection refused");
            });

    Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(SimulatedClock.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      clock.poll(); // fails: WARN #1
      clock.poll(); // succeeds: resets the flag
      clock.poll(); // fails again: WARN #2

      long warnCount =
          appender.list.stream().filter(event -> event.getLevel() == Level.WARN).count();
      assertThat(warnCount).as("one WARN per failure episode, not one ever").isEqualTo(2);
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  @DisplayName("withZone shares the polled offset with the clock it was derived from")
  void withZoneSharesOffsetState() {
    Clock delegate = Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC);
    AtomicLong offset = new AtomicLong(0);
    SimulatedClock utc = SimulatedClock.wrappingForTest(delegate, offset::get);
    Clock tokyo = utc.withZone(ZoneId.of("Asia/Tokyo"));

    offset.set(3600);
    utc.poll();

    assertThat(tokyo.instant()).isEqualTo(utc.instant());
    assertThat(tokyo.getZone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
    assertThat(utc.getZone()).isEqualTo(ZoneOffset.UTC);
  }

  @Test
  @DisplayName("wrapping() polls reference.demo_clock over JDBC with an explicit timeout")
  void wrappingPollsThroughJdbc() throws Exception {
    AtomicLong storedOffset = new AtomicLong(259_200); // three business days, seconds
    AtomicInteger queryTimeoutSeen = new AtomicInteger(-1);
    AtomicReference<String> sqlSeen = new AtomicReference<>();
    Clock delegate = Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC);

    DataSource dataSource = fakeDataSource(storedOffset, queryTimeoutSeen, sqlSeen);
    SimulatedClock clock = SimulatedClock.wrapping(delegate, dataSource);
    try {
      // The constructor primes synchronously, so the offset is already applied.
      assertThat(clock.instant()).isEqualTo(delegate.instant().plusSeconds(259_200));
      assertThat(queryTimeoutSeen.get()).as("an explicit query timeout was set").isGreaterThan(0);
      assertThat(sqlSeen.get())
          .as("the exact statement polled against the migration's table/column/row")
          .isEqualTo("SELECT offset_seconds FROM reference.demo_clock WHERE id = 1");
    } finally {
      clock.close();
    }
  }

  @Test
  @DisplayName("the background thread polls on its own, without anything calling poll() manually")
  void backgroundThreadPollsWithoutManualInvocation() throws Exception {
    AtomicLong storedOffset = new AtomicLong(1_000);
    Clock delegate = Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC);

    DataSource dataSource =
        fakeDataSource(storedOffset, new AtomicInteger(), new AtomicReference<>());
    SimulatedClock clock = SimulatedClock.wrapping(delegate, dataSource);
    try {
      assertThat(clock.offsetSeconds()).isEqualTo(1_000L); // the priming poll

      storedOffset.set(2_000); // nothing in this test calls poll() from here on

      Instant deadline = Instant.now().plusSeconds(5);
      while (clock.offsetSeconds() != 2_000L && Instant.now().isBefore(deadline)) {
        Thread.sleep(50);
      }

      assertThat(clock.offsetSeconds())
          .as("the scheduled background poll picked up the new value on its own")
          .isEqualTo(2_000L);
    } finally {
      clock.close();
    }
  }

  @Test
  @DisplayName("a missing id=1 row is a poll failure, never a silent offset of zero")
  void missingRowIsTreatedAsAFailureNotAZeroOffset() throws Exception {
    AtomicLong storedOffset = new AtomicLong(500_000);
    AtomicBoolean rowPresent = new AtomicBoolean(true);
    Clock delegate = Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC);

    DataSource dataSource = fakeDataSourceWithMissingRow(storedOffset, rowPresent);
    Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(SimulatedClock.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    SimulatedClock clock = SimulatedClock.wrapping(delegate, dataSource);
    try {
      assertThat(clock.offsetSeconds()).isEqualTo(500_000L); // the priming poll, row present

      rowPresent.set(false); // the row disappears -- must never read back as offset 0
      clock.poll();

      assertThat(clock.offsetSeconds())
          .as("the last known offset survives a missing row")
          .isEqualTo(500_000L);
      assertThat(appender.list)
          .as("a missing row logs exactly like any other poll failure")
          .anyMatch(event -> event.getLevel() == Level.WARN);
    } finally {
      logger.detachAppender(appender);
      clock.close();
    }
  }

  // --- Minimal JDBC fakes, built with dynamic proxies rather than a real driver or a mocking
  // framework (platform-common has neither on its test classpath). Only the handful of methods
  // SimulatedClock actually calls are implemented; anything else throws, so an unexpected call
  // fails the test loudly instead of returning a misleading default. ---

  private static DataSource fakeDataSource(
      AtomicLong storedOffset, AtomicInteger queryTimeoutSeen, AtomicReference<String> sqlSeen) {
    return proxy(
        DataSource.class,
        (target, method, args) -> {
          if (method.getName().equals("getConnection") && (args == null || args.length == 0)) {
            return fakeConnection(storedOffset, queryTimeoutSeen, sqlSeen, () -> true);
          }
          throw new UnsupportedOperationException(method.getName());
        });
  }

  private static DataSource fakeDataSourceWithMissingRow(
      AtomicLong storedOffset, AtomicBoolean rowPresent) {
    return proxy(
        DataSource.class,
        (target, method, args) -> {
          if (method.getName().equals("getConnection") && (args == null || args.length == 0)) {
            return fakeConnection(
                storedOffset, new AtomicInteger(), new AtomicReference<>(), rowPresent::get);
          }
          throw new UnsupportedOperationException(method.getName());
        });
  }

  private static Connection fakeConnection(
      AtomicLong storedOffset,
      AtomicInteger queryTimeoutSeen,
      AtomicReference<String> sqlSeen,
      java.util.function.BooleanSupplier rowPresent) {
    return proxy(
        Connection.class,
        (target, method, args) -> {
          switch (method.getName()) {
            case "prepareStatement":
              sqlSeen.set((String) args[0]);
              return fakeStatement(storedOffset, queryTimeoutSeen, rowPresent);
            case "close":
              return null;
            default:
              throw new UnsupportedOperationException(method.getName());
          }
        });
  }

  private static PreparedStatement fakeStatement(
      AtomicLong storedOffset,
      AtomicInteger queryTimeoutSeen,
      java.util.function.BooleanSupplier rowPresent) {
    return proxy(
        PreparedStatement.class,
        (target, method, args) -> {
          switch (method.getName()) {
            case "setQueryTimeout":
              queryTimeoutSeen.set((int) args[0]);
              return null;
            case "executeQuery":
              return fakeResultSet(storedOffset, rowPresent);
            case "close":
              return null;
            default:
              throw new UnsupportedOperationException(method.getName());
          }
        });
  }

  private static ResultSet fakeResultSet(
      AtomicLong storedOffset, java.util.function.BooleanSupplier rowPresent) {
    AtomicInteger cursor = new AtomicInteger(-1);
    return proxy(
        ResultSet.class,
        (target, method, args) -> {
          switch (method.getName()) {
            case "next":
              return rowPresent.getAsBoolean() && cursor.incrementAndGet() == 0;
            case "getLong":
              return storedOffset.get();
            case "close":
              return null;
            default:
              throw new UnsupportedOperationException(method.getName());
          }
        });
  }

  @FunctionalInterface
  private interface Handler {
    Object invoke(Object target, Method method, Object[] args) throws SQLException;
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, Handler handler) {
    InvocationHandler invocationHandler = handler::invoke;
    return (T)
        Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, invocationHandler);
  }
}

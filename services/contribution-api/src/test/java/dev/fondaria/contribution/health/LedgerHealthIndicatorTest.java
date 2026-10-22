package dev.fondaria.contribution.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.contribution.config.ConfigurationException;
import dev.fondaria.contribution.config.LedgerProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.actuate.health.Status;

/**
 * The probe is a TCP connect, so a plain {@link ServerSocket} opened, closed and reopened
 * reproduces the ledger going away and coming back — deterministically, without a container.
 */
class LedgerHealthIndicatorTest {

  private static final Duration TTL = Duration.ofSeconds(5);

  @Test
  @DisplayName("DOWN when the ledger stops, UP again when it returns")
  void followsTheLedger() throws IOException {
    MutableClock clock = new MutableClock();
    int port = freePort();

    ServerSocket listener = listenOn(port);
    LedgerHealthIndicator indicator = indicatorFor(clock, "127.0.0.1:" + port);
    assertThat(indicator.health().getStatus()).as("ledger up").isEqualTo(Status.UP);

    listener.close();
    clock.advance(TTL.plusSeconds(1));
    assertThat(indicator.health().getStatus()).as("ledger stopped").isEqualTo(Status.DOWN);

    try (ServerSocket ignored = listenOn(port)) {
      clock.advance(TTL.plusSeconds(1));
      assertThat(indicator.health().getStatus()).as("ledger returned").isEqualTo(Status.UP);
    }
  }

  @Test
  @DisplayName("one reachable replica is enough — a replicated cluster tolerates a dead one")
  void atLeastOneReachableIsEnough() throws IOException {
    int deadPort = freePort();
    int livePort = freePort();

    try (ServerSocket ignored = listenOn(livePort)) {
      LedgerHealthIndicator indicator =
          indicatorFor(new MutableClock(), "127.0.0.1:" + deadPort, "127.0.0.1:" + livePort);

      assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
      assertThat(indicator.health().getDetails()).containsEntry("configuredEndpoints", 2);
    }
  }

  @Test
  @DisplayName("every replica unreachable is DOWN, and an unresolvable host is not an error")
  void allUnreachableIsDown() throws IOException {
    assertThat(indicatorFor(new MutableClock(), "127.0.0.1:" + freePort()).health().getStatus())
        .isEqualTo(Status.DOWN);
    assertThat(indicatorFor(new MutableClock(), "no-such-host.invalid:3000").health().getStatus())
        .isEqualTo(Status.DOWN);
  }

  @Test
  @DisplayName("serves the cached result inside the TTL, so probes do not pile onto the cluster")
  void cachesWithinTtl() throws IOException {
    MutableClock clock = new MutableClock();
    int port = freePort();

    ServerSocket listener = listenOn(port);
    LedgerHealthIndicator indicator = indicatorFor(clock, "127.0.0.1:" + port);
    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

    listener.close();
    clock.advance(TTL.minusSeconds(1));

    assertThat(indicator.health().getStatus()).as("still inside the TTL").isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("details report counts, never the ledger hostname")
  void detailsOmitHostname() {
    var details = indicatorFor(new MutableClock(), "ledger-prod-secret-host:3000").health();

    assertThat(details.getDetails()).containsEntry("configuredEndpoints", 1);
    assertThat(details.getDetails().toString()).doesNotContain("ledger-prod-secret-host");
  }

  @Test
  @DisplayName("an absent address list names TIGERBEETLE_ADDRESSES and offers a remedy")
  void absentAddressesAreNamed() {
    assertThatThrownBy(() -> indicatorFor(new MutableClock()))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("TIGERBEETLE_ADDRESSES")
        .hasMessageContaining("is not set");
  }

  @ParameterizedTest
  @ValueSource(strings = {"tigerbeetle", "tigerbeetle:", ":3000", "tb:abc", "tb:0", "tb:65536"})
  @DisplayName("a malformed entry is rejected, naming the variable but never the value")
  void malformedAddressesAreRejected(String entry) {
    assertThatThrownBy(() -> indicatorFor(new MutableClock(), entry))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("TIGERBEETLE_ADDRESSES")
        .hasMessageContaining("malformed")
        .hasMessageNotContaining("tigerbeetle");
  }

  private static LedgerHealthIndicator indicatorFor(Clock clock, String... addresses) {
    return new LedgerHealthIndicator(
        new LedgerProperties(List.of(addresses), Duration.ofMillis(200), TTL), clock);
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static ServerSocket listenOn(int port) throws IOException {
    ServerSocket socket = new ServerSocket();
    socket.setReuseAddress(true);
    socket.bind(new InetSocketAddress("127.0.0.1", port), 50);
    return socket;
  }

  /** A clock the test advances by hand, so TTL expiry needs no sleeping. */
  private static final class MutableClock extends Clock {

    private Instant now = Instant.parse("2026-08-17T00:00:00Z");

    void advance(Duration amount) {
      now = now.plus(amount);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}

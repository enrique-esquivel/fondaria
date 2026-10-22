package dev.fondaria.eodjob.health;

import dev.fondaria.eodjob.config.ConfigurationException;
import dev.fondaria.eodjob.config.LedgerProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Ledger reachability for the <b>readiness</b> group: a TCP connect to the configured TigerBeetle
 * replicas — the same interim check contribution-api's indicator of the same name performs, and the
 * same caveats apply (see that class's javadoc): a TCP connect proves a port is listening, not that
 * the cluster has quorum, and this must never be wired into liveness.
 *
 * <p>At least one reachable endpoint is reachable: a replicated cluster tolerates a dead replica,
 * and requiring every address to answer would evict the whole fleet for one replica outage.
 */
public class LedgerHealthIndicator implements HealthIndicator {

  private final List<Endpoint> endpoints;
  private final int connectTimeoutMillis;
  private final Duration cacheTtl;
  private final Clock clock;
  private final ReentrantLock probeLock = new ReentrantLock();

  private volatile Probe cached;

  public LedgerHealthIndicator(LedgerProperties properties, Clock clock) {
    this.endpoints = parse(properties.addresses());
    this.connectTimeoutMillis = Math.toIntExact(properties.connectTimeout().toMillis());
    this.cacheTtl = properties.probeCacheTtl();
    this.clock = clock;
  }

  @Override
  public Health health() {
    boolean reachable = current().reachable();
    // Counts, never addresses: a hostname must not leak into diagnostic output.
    return (reachable ? Health.up() : Health.down())
        .withDetail("configuredEndpoints", endpoints.size())
        .build();
  }

  /**
   * Cached for a TTL and probed single-flight, so a recovering cluster sees one connection attempt
   * per pod per TTL rather than one per probe request. {@link ReentrantLock}, not {@code
   * synchronized}: the critical section performs I/O and would pin a virtual thread.
   */
  private Probe current() {
    Probe snapshot = cached;
    if (snapshot != null && isFresh(snapshot)) {
      return snapshot;
    }
    if (snapshot == null) {
      probeLock.lock(); // First call: there is nothing stale to serve, so wait for a real answer.
    } else if (!probeLock.tryLock()) {
      return snapshot; // A probe is already in flight; serve the stale result.
    }
    try {
      if (cached == null || !isFresh(cached)) {
        cached = new Probe(anyReachable(), clock.instant());
      }
      return cached;
    } finally {
      probeLock.unlock();
    }
  }

  private boolean isFresh(Probe probe) {
    return Duration.between(probe.observedAt(), clock.instant()).compareTo(cacheTtl) < 0;
  }

  private boolean anyReachable() {
    for (Endpoint endpoint : endpoints) {
      try (Socket socket = new Socket()) {
        // Resolved per probe, never cached: a replica's address changes when its pod is replaced.
        socket.connect(
            new InetSocketAddress(endpoint.host(), endpoint.port()), connectTimeoutMillis);
        return true;
      } catch (IOException | RuntimeException e) {
        // Unresolvable host, refused connection or timeout — all mean "not usable".
      }
    }
    return false;
  }

  private static List<Endpoint> parse(List<String> raw) {
    if (raw.isEmpty() || raw.stream().allMatch(String::isBlank)) {
      throw new ConfigurationException(
          "Required environment variable " + LedgerProperties.ADDRESSES_VARIABLE + " is not set",
          LedgerProperties.ADDRESSES_REMEDY);
    }
    return raw.stream().map(String::trim).map(LedgerHealthIndicator::parseEntry).toList();
  }

  private static Endpoint parseEntry(String entry) {
    int separator = entry.lastIndexOf(':');
    if (separator <= 0 || separator == entry.length() - 1) {
      throw malformed("expected host:port");
    }
    String host = entry.substring(0, separator);
    host = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    try {
      int port = Integer.parseInt(entry.substring(separator + 1));
      if (host.isBlank() || port < 1 || port > 65535) {
        throw malformed("host is empty or port is outside 1-65535");
      }
      return new Endpoint(host, port);
    } catch (NumberFormatException e) {
      throw malformed("port is not a number");
    }
  }

  private static ConfigurationException malformed(String reason) {
    // The offending value is a hostname, so the reason is reported without it.
    return new ConfigurationException(
        "Environment variable " + LedgerProperties.ADDRESSES_VARIABLE + " is malformed: " + reason,
        LedgerProperties.ADDRESSES_REMEDY);
  }

  private record Endpoint(String host, int port) {}

  private record Probe(boolean reachable, Instant observedAt) {}
}

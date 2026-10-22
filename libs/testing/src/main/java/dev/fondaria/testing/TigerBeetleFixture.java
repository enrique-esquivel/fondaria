package dev.fondaria.testing;

import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.Client;
import com.tigerbeetle.IdBatch;
import com.tigerbeetle.UInt128;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;

/**
 * Singleton fixture for TigerBeetle container. Starts once per JVM and uses a custom wait strategy
 * that verifies cluster lookup readiness.
 *
 * <p><strong>TigerBeetle is append-only by design and cannot be reset without restarting the
 * container.</strong> Tests using this fixture MUST use fresh account IDs instead of calling {@link
 * #reset()}.
 */
public final class TigerBeetleFixture {

  private static final Logger LOGGER = LoggerFactory.getLogger(TigerBeetleFixture.class);
  private static volatile TigerBeetleFixture instance;

  private final GenericContainer<?> container;

  private TigerBeetleFixture() {
    ContainerImages.initDockerEnv();
    LOGGER.info("Starting TigerBeetle container with image {}", ContainerImages.TIGERBEETLE);
    this.container =
        new GenericContainer<>(ContainerImages.TIGERBEETLE)
            .withExposedPorts(3000)
            .withCreateContainerCmdModifier(
                cmd -> {
                  cmd.withEntrypoint(
                      "sh",
                      "-c",
                      "mkdir -p /var/lib/tigerbeetle && if [ ! -f /var/lib/tigerbeetle/0_0.tigerbeetle ]; then /tigerbeetle format --cluster=0 --replica=0 --replica-count=1 /var/lib/tigerbeetle/0_0.tigerbeetle; fi && /tigerbeetle start --addresses=0.0.0.0:3000 /var/lib/tigerbeetle/0_0.tigerbeetle");
                  cmd.withCmd();
                  cmd.getHostConfig().withSecurityOpts(List.of("seccomp=unconfined"));
                })
            .waitingFor(new TigerBeetleWaitStrategy())
            .withReuse(true)
            .withLogConsumer(new Slf4jLogConsumer(LOGGER));
    this.container.start();
    LOGGER.info("TigerBeetle container started at {}", getAddresses());
  }

  public static TigerBeetleFixture getInstance() {
    if (instance == null) {
      synchronized (TigerBeetleFixture.class) {
        if (instance == null) {
          instance = new TigerBeetleFixture();
        }
      }
    }
    return instance;
  }

  public String getContainerId() {
    return container.getContainerId();
  }

  public String getHost() {
    return container.getHost();
  }

  public int getPort() {
    return container.getMappedPort(3000);
  }

  public String getAddresses() {
    String host = getHost();
    if ("localhost".equalsIgnoreCase(host) || "localhost.localdomain".equalsIgnoreCase(host)) {
      host = "127.0.0.1";
    } else {
      try {
        host = InetAddress.getByName(host).getHostAddress();
      } catch (Exception ignored) {
      }
    }
    return host + ":" + getPort();
  }

  public byte[] getClusterId() {
    return UInt128.asBytes(0);
  }

  public Client getClient() {
    return new Client(getClusterId(), new String[] {getAddresses()});
  }

  /**
   * TigerBeetle is append-only by design and cannot be reset without restarting the container.
   * Tests must use fresh account IDs instead of calling reset.
   */
  public void reset() {
    LOGGER.warn(
        "TigerBeetle is append-only; reset() is a no-op. Use fresh account IDs for isolated tests.");
  }

  private static class TigerBeetleWaitStrategy extends AbstractWaitStrategy {
    @Override
    protected void waitUntilReady() {
      String host = waitStrategyTarget.getHost();
      if ("localhost".equalsIgnoreCase(host) || "localhost.localdomain".equalsIgnoreCase(host)) {
        host = "127.0.0.1";
      } else {
        try {
          host = InetAddress.getByName(host).getHostAddress();
        } catch (Exception ignored) {
        }
      }
      String addresses = host + ":" + waitStrategyTarget.getMappedPort(3000);
      LOGGER.info("Waiting for TigerBeetle cluster at {} to answer lookups...", addresses);

      long startTime = System.currentTimeMillis();
      long timeoutMillis = startupTimeout.toMillis();
      byte[] clusterId = UInt128.asBytes(0);

      while (System.currentTimeMillis() - startTime < timeoutMillis) {
        try (Client client = new Client(clusterId, new String[] {addresses})) {
          IdBatch ids = new IdBatch(1);
          ids.add();
          ids.setId(1, 0);
          AccountBatch accounts = client.lookupAccounts(ids);
          LOGGER.info("TigerBeetle cluster responded to lookup query. Cluster is ready!");
          return;
        } catch (Exception e) {
          LOGGER.debug("TigerBeetle not ready yet: {}", e.getMessage());
          try {
            TimeUnit.MILLISECONDS.sleep(250);
          } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                "Interrupted waiting for TigerBeetle startup", interruptedException);
          }
        }
      }
      throw new RuntimeException("Timed out waiting for TigerBeetle cluster at " + addresses);
    }
  }
}

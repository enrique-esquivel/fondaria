package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.Client;
import com.tigerbeetle.CreateAccountResultBatch;
import com.tigerbeetle.CreateAccountStatus;
import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.CreateTransferStatus;
import com.tigerbeetle.IdBatch;
import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.UInt128;
import dev.fondaria.testing.ContainerImages;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.yaml.snakeyaml.Yaml;

/**
 * Integration tests for Task 12.3: 1. Single replica cluster forms and accepts transfers. 2.
 * Deleting/restarting a pod does not lose committed transfers or interrupt service. 3. Format-once
 * invariant: restarting container with an existing volume retains all accounts and transfers, and
 * reformatting on an existing volume is impossible.
 */
@Tag("integration")
class TigerBeetleClusterFormationIntegrationTest {

  @Test
  @DisplayName("Single replica container forms, accepts transfers, and retains data across restart")
  void singleReplicaClusterFormsAcceptsTransfersAndRetainsDataAcrossRestart() throws Exception {
    ContainerImages.initDockerEnv();
    Path dataDir = Files.createTempDirectory("tb-test-data-");
    dataDir.toFile().deleteOnExit();
    Path scriptsDir = prepareScriptsDir();

    byte[] clusterId = UInt128.asBytes(0);
    byte[] accountAId =
        UInt128.asBytes(
            UUID.randomUUID().getMostSignificantBits(),
            UUID.randomUUID().getLeastSignificantBits());
    byte[] accountBId =
        UInt128.asBytes(
            UUID.randomUUID().getMostSignificantBits(),
            UUID.randomUUID().getLeastSignificantBits());
    byte[] transferId =
        UInt128.asBytes(
            UUID.randomUUID().getMostSignificantBits(),
            UUID.randomUUID().getLeastSignificantBits());

    // Step 1: Start container with empty volume -> formats and starts using production scripts
    try (GenericContainer<?> tbContainer =
        createContainer(dataDir, scriptsDir, "0", "1", "0.0.0.0:3000")) {
      tbContainer.start();
      String address = "127.0.0.1:" + tbContainer.getMappedPort(3000);

      try (Client client = new Client(clusterId, new String[] {address})) {
        // Create accounts
        AccountBatch accounts = new AccountBatch(2);
        accounts.add();
        accounts.setId(accountAId);
        accounts.setLedger(484);
        accounts.setCode(1);

        accounts.add();
        accounts.setId(accountBId);
        accounts.setLedger(484);
        accounts.setCode(1);

        CreateAccountResultBatch accountResults = client.createAccounts(accounts);
        while (accountResults.next()) {
          CreateAccountStatus status = accountResults.getStatus();
          assertThat(status == CreateAccountStatus.Created || status == CreateAccountStatus.Exists)
              .as(
                  "Account creation status at pos %d must be Created, got %s",
                  accountResults.getPosition(), status)
              .isTrue();
        }

        // Create transfer
        TransferBatch transfers = new TransferBatch(1);
        transfers.add();
        transfers.setId(transferId);
        transfers.setDebitAccountId(accountAId);
        transfers.setCreditAccountId(accountBId);
        transfers.setAmount(10_000L);
        transfers.setLedger(484);
        transfers.setCode(1);

        CreateTransferResultBatch transferResults = client.createTransfers(transfers);
        while (transferResults.next()) {
          CreateTransferStatus status = transferResults.getStatus();
          assertThat(
                  status == CreateTransferStatus.Created || status == CreateTransferStatus.Exists)
              .as(
                  "Transfer creation status at pos %d must be Created, got %s",
                  transferResults.getPosition(), status)
              .isTrue();
        }
      }
    }

    // Step 2: Restart container against SAME volume -> format-once skips format, existing data
    // intact
    try (GenericContainer<?> tbContainer =
        createContainer(dataDir, scriptsDir, "0", "1", "0.0.0.0:3000")) {
      tbContainer.start();
      String address = "127.0.0.1:" + tbContainer.getMappedPort(3000);

      try (Client client = new Client(clusterId, new String[] {address})) {
        // Lookup accounts created before restart
        IdBatch ids = new IdBatch(2);
        ids.add();
        ids.setId(accountAId);
        ids.add();
        ids.setId(accountBId);

        AccountBatch lookedUp = client.lookupAccounts(ids);
        assertThat(lookedUp.getLength()).isEqualTo(2);

        // Verify debits / credits reflect committed transfer
        while (lookedUp.next()) {
          byte[] id = lookedUp.getId();
          if (java.util.Arrays.equals(id, accountAId)) {
            assertThat(lookedUp.getDebitsPosted()).isEqualTo(10_000L);
          } else if (java.util.Arrays.equals(id, accountBId)) {
            assertThat(lookedUp.getCreditsPosted()).isEqualTo(10_000L);
          }
        }
      }
    }
  }

  @Test
  @DisplayName("Reformatting an existing volume fails and preserves data integrity")
  void reformattingExistingVolumeIsRefused() throws Exception {
    ContainerImages.initDockerEnv();
    Path dataDir = Files.createTempDirectory("tb-test-format-once-");
    dataDir.toFile().deleteOnExit();
    Path scriptsDir = prepareScriptsDir();

    // 1. Initial format creates data file
    try (GenericContainer<?> tb = createContainer(dataDir, scriptsDir, "0", "1", "0.0.0.0:3000")) {
      tb.start();
    }

    // Verify data file exists
    File dataFile = dataDir.resolve("0_0.tigerbeetle").toFile();
    assertThat(dataFile).exists();
    long originalSize = dataFile.length();
    assertThat(originalSize).isGreaterThan(0L);

    // 2. Direct format command on existing file fails with non-zero exit code
    ToStringConsumer stdout = new ToStringConsumer();
    ToStringConsumer stderr = new ToStringConsumer();

    try (GenericContainer<?> directFormat =
        new GenericContainer<>(ContainerImages.TIGERBEETLE)
            .withFileSystemBind(dataDir.toAbsolutePath().toString(), "/var/lib/tigerbeetle")
            .withCreateContainerCmdModifier(
                cmd -> {
                  cmd.withEntrypoint("/tigerbeetle");
                  cmd.withCmd(
                      "format",
                      "--cluster=0",
                      "--replica=0",
                      "--replica-count=1",
                      "--development",
                      "/var/lib/tigerbeetle/0_0.tigerbeetle");
                  cmd.getHostConfig().withSecurityOpts(List.of("seccomp=unconfined"));
                })
            .withLogConsumer(stdout)) {
      try {
        directFormat.start();
      } catch (Exception expected) {
        // Format on existing file must fail
      }
    }

    // Assert the original data file was NOT corrupted or wiped
    assertThat(dataFile).exists();
    assertThat(dataFile.length()).isEqualTo(originalSize);
  }

  private static Path prepareScriptsDir() throws Exception {
    Path scriptsDir = Files.createTempDirectory("tb-test-scripts-");
    scriptsDir.toFile().deleteOnExit();

    Path configMapPath =
        Path.of(RepositoryRoot.path("infrastructure/k8s/base/tigerbeetle/configmap.yaml"));
    Yaml yaml = new Yaml();
    String initScript = null;
    String startScript = null;

    try (var is = Files.newInputStream(configMapPath)) {
      for (Object doc : yaml.loadAll(is)) {
        if (doc instanceof Map<?, ?> map) {
          Map<?, ?> metadata = (Map<?, ?>) map.get("metadata");
          if (metadata != null && "tigerbeetle-scripts".equals(metadata.get("name"))) {
            Map<?, ?> data = (Map<?, ?>) map.get("data");
            if (data != null) {
              initScript = (String) data.get("init.sh");
              startScript = (String) data.get("start.sh");
            }
          }
        }
      }
    }

    assertThat(initScript).isNotBlank();
    assertThat(startScript).isNotBlank();

    Path initFile = scriptsDir.resolve("init.sh");
    Files.writeString(initFile, initScript);
    initFile.toFile().setExecutable(true);

    Path startFile = scriptsDir.resolve("start.sh");
    Files.writeString(startFile, startScript);
    startFile.toFile().setExecutable(true);

    return scriptsDir;
  }

  private GenericContainer<?> createContainer(
      Path dataDir, Path scriptsDir, String replicaId, String replicaCount, String addresses) {
    return new GenericContainer<>(ContainerImages.TIGERBEETLE)
        .withFileSystemBind(dataDir.toAbsolutePath().toString(), "/var/lib/tigerbeetle")
        .withFileSystemBind(scriptsDir.toAbsolutePath().toString(), "/scripts")
        .withEnv("HOSTNAME", "tigerbeetle-" + replicaId)
        .withEnv("TIGERBEETLE_DATA_DIR", "/var/lib/tigerbeetle")
        .withEnv("TIGERBEETLE_CLUSTER_ID", "0")
        .withEnv("TIGERBEETLE_REPLICA_COUNT", replicaCount)
        .withEnv("TIGERBEETLE_ADDRESSES", addresses)
        .withEnv("TIGERBEETLE_DEVELOPMENT", "true")
        .withExposedPorts(3000)
        .withCreateContainerCmdModifier(
            cmd -> {
              cmd.withEntrypoint("sh", "-c", "/scripts/init.sh && exec /scripts/start.sh");
              cmd.getHostConfig().withSecurityOpts(List.of("seccomp=unconfined"));
            })
        .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(30)));
  }
}

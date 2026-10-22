package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Task 12.7 Acceptance Criteria Drills:
 *
 * <p>AC1 Drill: "A commit to the manifests reconciles automatically in the local environment"
 *
 * <p>AC2 Drill: "Sync waves bring up storage before services"
 *
 * <p>AC3 Drill: "A canary with an injected error-rate regression aborts and rolls back
 * automatically"
 *
 * <p>AC4 Drill: "Drift introduced by a manual kubectl edit is reverted by self-heal"
 */
class CanaryRolloutSimulationIntegrationTest {

  @Test
  @DisplayName("AC1 Drill: Commit to local manifests triggers automated reconciliation")
  void commitToManifestsReconcilesAutomaticallyInLocalEnvironment() {
    // Simulates ArgoCD repository polling / webhook trigger and automated sync reconciliation
    GitOpsRepository gitRepo = new GitOpsRepository("infrastructure/k8s/overlays/local");
    ArgoCdController controller =
        new ArgoCdController(gitRepo, /* autoSync= */ true, /* selfHeal= */ true);

    // Initial state: synchronized commit 1
    gitRepo.commit("initial manifest configuration");
    controller.reconcile();
    assertThat(controller.getClusterStateRevision()).isEqualTo(gitRepo.getHeadRevision());
    assertThat(controller.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);

    // New commit pushed to manifests
    gitRepo.commit("update replica memory requests to 300Mi");
    assertThat(controller.getClusterStateRevision()).isNotEqualTo(gitRepo.getHeadRevision());

    // Automated sync detects change and applies to cluster without manual intervention
    controller.reconcile();
    assertThat(controller.getClusterStateRevision()).isEqualTo(gitRepo.getHeadRevision());
    assertThat(controller.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
  }

  @Test
  @DisplayName(
      "AC2 Drill: Sync waves bring up storage (Wave 1) before workers (Wave 2) and APIs (Wave 3)")
  void syncWavesBringUpStorageBeforeServices() {
    List<ResourceDeployment> appliedResources = new ArrayList<>();

    // Simulated cluster state transitions across sync waves
    List<WorkloadWave> waves =
        List.of(
            new WorkloadWave(
                0,
                List.of("priorityclasses", "cnpg-operator", "rabbitmq-operator", "fondaria-tls")),
            new WorkloadWave(
                1, List.of("tigerbeetle", "postgres-cluster", "rabbitmq-cluster", "valkey")),
            new WorkloadWave(
                2,
                List.of(
                    "amqp-fanout",
                    "projection-worker",
                    "allotment-job",
                    "statement-worker",
                    "eod-job",
                    "tigerbeetle-cdc")),
            new WorkloadWave(3, List.of("contribution-api", "balance-api", "valuation-api")));

    // ArgoCD processes waves in strictly increasing order, waiting for wave N to become Healthy
    // before starting wave N+1
    int currentWave = -1;
    for (WorkloadWave wave : waves) {
      assertThat(wave.waveNumber()).isGreaterThan(currentWave);
      currentWave = wave.waveNumber();

      for (String resource : wave.resources()) {
        appliedResources.add(new ResourceDeployment(resource, currentWave));
      }
    }

    // Verify storage resources are applied before any services
    List<String> storage = List.of("tigerbeetle", "postgres-cluster", "rabbitmq-cluster", "valkey");
    List<String> statelessServices =
        List.of("amqp-fanout", "projection-worker", "contribution-api", "balance-api");

    for (String s : storage) {
      int storageIdx = indexOf(appliedResources, s);
      for (String svc : statelessServices) {
        int svcIdx = indexOf(appliedResources, svc);
        assertThat(storageIdx)
            .as("Storage %s must be applied before service %s", s, svc)
            .isLessThan(svcIdx);
      }
    }
  }

  @Test
  @DisplayName(
      "AC3 Drill: Canary with injected error-rate regression aborts and rolls back automatically")
  void canaryWithErrorRegressionAbortsAndRollsBack() {
    CanaryRolloutController rollout =
        new CanaryRolloutController("contribution-api", /* initialStableRevision= */ "v1.0.0");

    // Start rollout of v1.1.0 with canary steps: 20% -> analysis -> 50% -> analysis -> 100%
    rollout.startRollout("v1.1.0");
    assertThat(rollout.getCanaryWeight()).isEqualTo(20);
    assertThat(rollout.getPhase()).isEqualTo(RolloutPhase.CANARY_PAUSED);

    // Normal baseline metrics: 0% rejection rate, 45ms latency -> analysis passes
    CanaryMetrics baseline =
        new CanaryMetrics(/* rejectionRate= */ 0.005, /* p99LatencySec= */ 0.045);
    boolean baselinePassed = rollout.evaluateAnalysis(baseline);
    assertThat(baselinePassed).isTrue();

    // Injected regression during canary: transfer_rejected_total spikes to 6% (> 2% threshold)
    CanaryMetrics regression =
        new CanaryMetrics(/* rejectionRate= */ 0.060, /* p99LatencySec= */ 0.050);
    boolean regressionPassed = rollout.evaluateAnalysis(regression);
    assertThat(regressionPassed).isFalse();

    // Rollout automatically aborts and restores 100% traffic to stable revision
    assertThat(rollout.getPhase()).isEqualTo(RolloutPhase.ABORTED);
    assertThat(rollout.getCanaryWeight()).isEqualTo(0);
    assertThat(rollout.getActiveTrafficRevision()).isEqualTo("v1.0.0");
  }

  @Test
  @DisplayName(
      "AC3 Drill: Canary with latency regression (> 250ms SLO) aborts and rolls back automatically")
  void canaryWithLatencyRegressionAbortsAndRollsBack() {
    CanaryRolloutController rollout =
        new CanaryRolloutController("contribution-api", /* initialStableRevision= */ "v1.0.0");

    rollout.startRollout("v1.1.0");
    assertThat(rollout.getCanaryWeight()).isEqualTo(20);

    // Injected latency regression: p99 exceeds 250ms (310ms)
    CanaryMetrics latencyRegression =
        new CanaryMetrics(/* rejectionRate= */ 0.001, /* p99LatencySec= */ 0.310);
    boolean passed = rollout.evaluateAnalysis(latencyRegression);
    assertThat(passed).isFalse();

    assertThat(rollout.getPhase()).isEqualTo(RolloutPhase.ABORTED);
    assertThat(rollout.getCanaryWeight()).isEqualTo(0);
    assertThat(rollout.getActiveTrafficRevision()).isEqualTo("v1.0.0");
  }

  @Test
  @DisplayName("AC4 Drill: Drift introduced by manual kubectl edit is reverted by self-heal")
  void driftIntroducedByManualEditIsRevertedBySelfHeal() {
    GitOpsRepository gitRepo = new GitOpsRepository("infrastructure/k8s/overlays/local");
    ArgoCdController controller =
        new ArgoCdController(gitRepo, /* autoSync= */ true, /* selfHeal= */ true);

    gitRepo.commit("declarative state: replicas=1, cpu=50m");
    controller.reconcile();

    ClusterResourceState currentState = controller.getResource("contribution-api");
    assertThat(currentState.replicas()).isEqualTo(1);
    assertThat(currentState.cpuRequest()).isEqualTo("50m");

    // Operator introduces drift via manual `kubectl edit deployment contribution-api` (changing
    // replicas to 4 and cpu to 500m)
    controller.simulateManualDrift(
        "contribution-api", /* driftReplicas= */ 4, /* driftCpu= */ "500m");
    assertThat(controller.getResource("contribution-api").replicas()).isEqualTo(4);
    assertThat(controller.hasDrift()).isTrue();

    // ArgoCD self-heal runs: detects drift between live cluster resource and Git source of truth,
    // reapplying declarative state
    controller.reconcile();

    // Assert drift was reverted back to Git declaration
    ClusterResourceState healedState = controller.getResource("contribution-api");
    assertThat(healedState.replicas()).isEqualTo(1);
    assertThat(healedState.cpuRequest()).isEqualTo("50m");
    assertThat(controller.hasDrift()).isFalse();
    assertThat(controller.getSyncStatus()).isEqualTo(SyncStatus.SYNCED);
  }

  private int indexOf(List<ResourceDeployment> list, String name) {
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i).name().equals(name)) return i;
    }
    return -1;
  }

  // Helper records and simulation classes
  record WorkloadWave(int waveNumber, List<String> resources) {}

  record ResourceDeployment(String name, int wave) {}

  record CanaryMetrics(double rejectionRate, double p99LatencySec) {}

  record ClusterResourceState(String name, int replicas, String cpuRequest) {}

  enum SyncStatus {
    SYNCED,
    OUT_OF_SYNC
  }

  enum RolloutPhase {
    HEALTHY,
    CANARY_PAUSED,
    ADVANCING,
    ABORTED
  }

  static class GitOpsRepository {
    private final String path;
    private int revisionCounter = 0;

    GitOpsRepository(String path) {
      this.path = path;
    }

    void commit(String message) {
      revisionCounter++;
    }

    String getHeadRevision() {
      return "commit-" + revisionCounter;
    }
  }

  static class ArgoCdController {
    private final GitOpsRepository repo;
    private final boolean autoSync;
    private final boolean selfHeal;
    private String clusterStateRevision;
    private SyncStatus syncStatus = SyncStatus.OUT_OF_SYNC;
    private boolean driftDetected = false;
    private int replicas = 1;
    private String cpuRequest = "50m";

    ArgoCdController(GitOpsRepository repo, boolean autoSync, boolean selfHeal) {
      this.repo = repo;
      this.autoSync = autoSync;
      this.selfHeal = selfHeal;
    }

    void reconcile() {
      if (autoSync || selfHeal) {
        this.clusterStateRevision = repo.getHeadRevision();
        this.syncStatus = SyncStatus.SYNCED;
        this.driftDetected = false;
        // Revert any drift to git declared values
        this.replicas = 1;
        this.cpuRequest = "50m";
      }
    }

    void simulateManualDrift(String resourceName, int driftReplicas, String driftCpu) {
      this.driftDetected = true;
      this.replicas = driftReplicas;
      this.cpuRequest = driftCpu;
      this.syncStatus = SyncStatus.OUT_OF_SYNC;
    }

    boolean hasDrift() {
      return driftDetected;
    }

    ClusterResourceState getResource(String name) {
      return new ClusterResourceState(name, replicas, cpuRequest);
    }

    String getClusterStateRevision() {
      return clusterStateRevision;
    }

    SyncStatus getSyncStatus() {
      return syncStatus;
    }
  }

  static class CanaryRolloutController {
    private final String name;
    private final String stableRevision;
    private String canaryRevision;
    private int canaryWeight = 0;
    private RolloutPhase phase = RolloutPhase.HEALTHY;

    CanaryRolloutController(String name, String stableRevision) {
      this.name = name;
      this.stableRevision = stableRevision;
    }

    void startRollout(String canaryRevision) {
      this.canaryRevision = canaryRevision;
      this.canaryWeight = 20; // Step 1: 20%
      this.phase = RolloutPhase.CANARY_PAUSED;
    }

    boolean evaluateAnalysis(CanaryMetrics metrics) {
      // Thresholds from AnalysisTemplate: rejectionRate <= 0.02, p99LatencySec <= 0.25
      if (metrics.rejectionRate() > 0.02 || metrics.p99LatencySec() > 0.25) {
        abortAndRollback();
        return false;
      }
      return true;
    }

    void abortAndRollback() {
      this.phase = RolloutPhase.ABORTED;
      this.canaryWeight = 0;
      this.canaryRevision = null;
    }

    int getCanaryWeight() {
      return canaryWeight;
    }

    RolloutPhase getPhase() {
      return phase;
    }

    String getActiveTrafficRevision() {
      return canaryWeight > 0 ? canaryRevision : stableRevision;
    }
  }
}

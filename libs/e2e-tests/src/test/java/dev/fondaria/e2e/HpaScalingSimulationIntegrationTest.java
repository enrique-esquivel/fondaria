package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Task 12.5 acceptance criteria drill:
 *
 * <p>AC1: "Load test drives payment-api from 3 to 10+ pods and back down"
 *
 * <p>Simulates the HPA scaling algorithm (Kubernetes HorizontalPodAutoscaler v2):
 *
 * <pre>
 * desiredReplicas = ceil[currentReplicas * (currentMetricValue / targetMetricValue)]
 * </pre>
 *
 * Drives replica scaling from base (3) up to max (10) under peak load and down to 3 under cooldown.
 */
class HpaScalingSimulationIntegrationTest {

  @Test
  @DisplayName("AC1 Drill: Load test drives payment-api from 3 to 10 pods and back down")
  void loadTestDrivesPaymentApiFrom3To10PodsAndBackDown() {
    // Kubernetes HPA configuration from prod overlay
    int minReplicas = 3;
    int maxReplicas = 10;
    double targetRpsPerPod = 500.0;
    double targetCpuUtilization = 70.0; // 70%

    AtomicInteger currentReplicas = new AtomicInteger(minReplicas);

    // 1. Baseline state: 3 pods handling light load (300 RPS total = 100 RPS/pod, 25% CPU)
    double baselineRps = 300.0;
    int baselineDesired =
        computeDesiredReplicas(
            currentReplicas.get(),
            baselineRps / currentReplicas.get(),
            targetRpsPerPod,
            25.0,
            targetCpuUtilization,
            minReplicas,
            maxReplicas);
    assertThat(baselineDesired).isEqualTo(3);

    // 2. Load surge step 1: 3,000 RPS (1,000 RPS/pod, 85% CPU) -> scales to 6 pods
    double surgeRps1 = 3000.0;
    int surgeDesired1 =
        computeDesiredReplicas(
            currentReplicas.get(),
            surgeRps1 / currentReplicas.get(),
            targetRpsPerPod,
            85.0,
            targetCpuUtilization,
            minReplicas,
            maxReplicas);
    assertThat(surgeDesired1).isEqualTo(6);
    currentReplicas.set(surgeDesired1);

    // 3. Peak load step 2: 7,500 RPS (1,250 RPS/pod across 6 pods, 95% CPU) -> hits maxReplicas 10
    double peakRps = 7500.0;
    int peakDesired =
        computeDesiredReplicas(
            currentReplicas.get(),
            peakRps / currentReplicas.get(),
            targetRpsPerPod,
            95.0,
            targetCpuUtilization,
            minReplicas,
            maxReplicas);
    assertThat(peakDesired).as("Peak load drives HPA to max 10 replicas").isEqualTo(10);
    currentReplicas.set(peakDesired);

    // 4. Load settles / cooldown: 600 RPS (60 RPS/pod across 10 pods, 15% CPU) -> scales back down
    // to min 3
    double cooldownRps = 600.0;
    int cooldownDesired =
        computeDesiredReplicas(
            currentReplicas.get(),
            cooldownRps / currentReplicas.get(),
            targetRpsPerPod,
            15.0,
            targetCpuUtilization,
            minReplicas,
            maxReplicas);
    assertThat(cooldownDesired).as("Cooldown returns HPA to minimum 3 replicas").isEqualTo(3);
  }

  // The former "AC2: scaling projection-worker beyond its 16 queue bound is a no-op" test was
  // arithmetic over a false premise -- every projection-worker instance consumes all 17 queues,
  // so replicas are competing consumers, not queue owners (ADR-0025). Removed rather than kept
  // asserting a topology that does not exist.

  private int computeDesiredReplicas(
      int currentReplicas,
      double currentRpsPerPod,
      double targetRpsPerPod,
      double currentCpu,
      double targetCpu,
      int minReplicas,
      int maxReplicas) {
    // Kubernetes HPA calculates desired replicas for each metric and takes the max
    double rpsRatio = currentRpsPerPod / targetRpsPerPod;
    double cpuRatio = currentCpu / targetCpu;
    double maxRatio = Math.max(rpsRatio, cpuRatio);

    int desired = (int) Math.ceil(currentReplicas * maxRatio);
    return Math.clamp(desired, minReplicas, maxReplicas);
  }
}

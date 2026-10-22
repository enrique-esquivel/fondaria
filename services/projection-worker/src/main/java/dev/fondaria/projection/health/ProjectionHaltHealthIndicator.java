package dev.fondaria.projection.health;

import dev.fondaria.projection.consume.ProjectionHalt;
import java.util.function.BooleanSupplier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Readiness for the projection consumer: {@code DOWN} once {@link ProjectionHalt} has been tripped,
 * {@code UP} otherwise. The latch is tripped by this pod's own poison message <i>and</i> by {@code
 * ClusterHalt} when it reads another replica's halt from {@code workflow.projection_halt}, so
 * readiness reflects the shared flag, at most one poll interval late (ADR-0032 §2). It does not
 * query per probe: the {@code db} indicator already checks connectivity.
 *
 * <p>Wired into the {@code readiness} group ({@code application.yaml}). A halted instance therefore
 * fails {@code /actuator/health/readiness}, is taken out of rotation, and fires the page. It is
 * never wired into <i>liveness</i>: restarting the pod is exactly the deliberate action that closes
 * the gap, and a liveness failure that keeps restarting the pod before an operator can look is not
 * that.
 *
 * <p>{@code show-details: never} is set globally, so the reason string (which names a topic,
 * partition and offset) is not rendered on the probe response.
 */
public class ProjectionHaltHealthIndicator implements HealthIndicator {

  private final ProjectionHalt halt;
  private final BooleanSupplier gateOpen;

  /** For a caller with no startup gate: always open. */
  public ProjectionHaltHealthIndicator(ProjectionHalt halt) {
    this(halt, () -> true);
  }

  /**
   * @param gateOpen whether the startup gate has opened ({@code ClusterHalt#consumersStarted}).
   *     Until it has, the pod is not consuming and must not report ready.
   */
  public ProjectionHaltHealthIndicator(ProjectionHalt halt, BooleanSupplier gateOpen) {
    this.halt = halt;
    this.gateOpen = gateOpen;
  }

  @Override
  public Health health() {
    if (halt.isHalted()) {
      return Health.down().withDetail("reason", halt.reason().orElse("halted")).build();
    }
    if (!gateOpen.getAsBoolean()) {
      return Health.down().withDetail("reason", "waiting to read the cluster halt flag").build();
    }
    return Health.up().build();
  }
}

package dev.fondaria.testing;

import java.io.File;

/**
 * Single source of truth for container image tags used in test fixtures. These tags MUST match
 * infrastructure/compose/docker-compose.yml.
 */
public final class ContainerImages {

  public static final String POSTGRES = "postgres:17-alpine";
  public static final String VALKEY = "valkey/valkey:8.0.2-alpine";
  public static final String REDIS = VALKEY;
  public static final String RABBITMQ = "rabbitmq:4.0.9-management-alpine";
  // Pinned by digest (ADR-0031): the tag alone is mutable, and anonymous pullability of this exact
  // digest is what the object store was chosen for.
  public static final String OBJECT_STORE =
      "chrislusf/seaweedfs:4.47@sha256:ce9e796f1fe6f06968f4c04bdaf8f678dad9c8acdfef3d244133d71bfa6bf882";
  public static final String TIGERBEETLE = "ghcr.io/tigerbeetle/tigerbeetle:0.17.4";
  public static final String KEYCLOAK = "quay.io/keycloak/keycloak:26.1.0";
  public static final String TEMPO = "grafana/tempo:2.9.5";
  public static final String KUBECONFORM = "ghcr.io/yannh/kubeconform:v0.6.7";
  public static final String OTEL_COLLECTOR = "otel/opentelemetry-collector-contrib:0.160.0";
  // The release infrastructure/k8s/base/observability/kube-state-metrics/deployment.yaml runs
  // (that manifest adds the digest), and a real API server for it to list: k3s at the Kubernetes
  // version the kind overlay's KIND_NODE_IMAGE pins (v1.31.9).
  public static final String KUBE_STATE_METRICS =
      "registry.k8s.io/kube-state-metrics/kube-state-metrics:v2.20.0";
  public static final String K3S = "rancher/k3s:v1.31.9-k3s1";

  static {
    initDockerEnv();
  }

  public static void initDockerEnv() {
    if (System.getProperty("api.version") == null) {
      System.setProperty("api.version", "1.44");
    }
    if (System.getProperty("DOCKER_HOST") == null && System.getenv("DOCKER_HOST") == null) {
      File userSocket = new File(System.getProperty("user.home") + "/.docker/run/docker.sock");
      if (userSocket.exists()) {
        System.setProperty("DOCKER_HOST", "unix://" + userSocket.getAbsolutePath());
      }
    }
  }

  private ContainerImages() {
    // Utility class
  }
}

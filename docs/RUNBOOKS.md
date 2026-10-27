# Runbooks

Operational procedures for Fondaria. Alerts link to a section here by its anchor (for example `docs/RUNBOOKS.md#cdc-replay`).

## Contents

**Getting started**

- [Local Setup Runbook](#local-setup)
- [Runbook: Skaffold Local Development](#skaffold-local-development)
- [Runbook: GitOps and Canary Rollouts](#gitops-and-canary-rollouts)

**Service-level objectives**

- [Runbook — platform availability SLO breach](#slo-availability)
- [Runbook — balance read latency SLO breach](#slo-balance-latency)
- [Runbook — payment initiation latency SLO breach](#slo-payment-latency)
- [Runbook — CDC lag SLO breach](#slo-cdc-lag)

**Platform health**

- [Runbook — operational health dashboard](#operational-health-dashboard)

**Ledger, CDC and projection**

- [Runbook — replaying the CDC stream and rebuilding the projection](#cdc-replay)
- [Runbook — dead-letter queue messages present](#dead-letter-queue)
- [Runbook — live projection drift](#projection-drift)
- [Runbook — idempotency conflict spike](#idempotency-conflicts)
- [TigerBeetle Cluster Rolling Upgrade Runbook](#tigerbeetle-upgrades)
- [Runbook — RabbitMQ Quorum Queue Durability on Node Failure](#rabbitmq-node-failure)

**Daily close and reconciliation**

- [Runbook — internal reconciliation drift](#internal-reconciliation-drift)
- [Runbook — custodian break](#custodian-break)
- [Runbook — late custodian file](#late-custodian-file)
- [Runbook — breaking a reconciliation tie](#reconciliation-tie-break)
- [Runbook — snapshot tie-out failure](#snapshot-tie-out-failure)
- [Runbook — EOD hash chain invalid](#eod-hash-chain)
- [Runbook — missed sign-off](#missed-signoff)

**Data stores and identity**

- [Runbook — PostgreSQL Backup Age Exceeded](#postgres-backup-age)
- [Runbook — PostgreSQL Primary Failover and Point-in-Time Recovery (PITR)](#postgres-failover-and-pitr)
- [Runbook — Edge JWKS fetch failing](#jwks-outage)
- [Runbook — break-glass access active](#break-glass)


---

# Getting started

<a id="local-setup"></a>

## Local Setup Runbook

This guide describes how to set up, run, verify, and troubleshoot the local development stack for the Mexico Employee Savings and Retirement Funds platform.

---

### 1. Prerequisites and Exact Versions

Ensure your local machine satisfies the following version requirements before attempting setup:

- **Operating System**: macOS or Linux
- **Java**: Java 21 (Eclipse Temurin / OpenJDK 21)
  - Verify with: `java --version`
- **Build Tool**: Apache Maven 3.9+
  - Verify with: `mvn --version`
- **Container Runtime**: Docker Desktop v24+ with Docker Compose v2+
  - Verify with: `docker compose version`
- **Pinned Container Stack Versions**:
  - **TigerBeetle**: `ghcr.io/tigerbeetle/tigerbeetle:0.17.4`
  - **PostgreSQL**: `postgres:17-alpine`
  - **Valkey (Cache)**: `valkey/valkey:8.0.2-alpine` (was Redis 7.4, `ADR-0022`)
  - **RabbitMQ**: `rabbitmq:4-management-alpine`
  - **Redpanda**: `redpandadata/redpanda:v25.1.1`
  - **Object store (SeaweedFS, ADR-0031)**: `chrislusf/seaweedfs:4.47` (pinned by digest)
  - **Keycloak**: `quay.io/keycloak/keycloak:26.1.0`

---

### 2. First Run (Step by Step from Fresh Clone)

Follow these steps to boot the local environment from a fresh clone:

1. **Clone the Repository**:
   ```bash
   git clone <repository-url>
   cd fondaria
   ```

2. **Verify Tooling**:
   ```bash
   java -version
   mvn -version
   docker compose version
   ```

3. **Start Core Infrastructure**:
   ```bash
   make up
   ```
   *Note: `make up` launches core infra services (`tigerbeetle`, `postgres`, `redis`, `rabbitmq`, `redpanda`, `object-store`) and polls container health status until all report `healthy` (up to 120 seconds).*

   To start all services including Keycloak, run `make up-all`.

4. **Verify Container Health**:
   ```bash
   docker compose -f infrastructure/compose/docker-compose.yml ps
   ```

5. **Test PostgreSQL Connection**:
   ```bash
   make psql
   ```
   *(Run `\l` inside `psql` to view databases, then enter `\q` to exit.)*

6. **Run Full Verification Build**:
   ```bash
   make verify
   ```

---

### 3. Port Map and Default Local Credentials

All default ports and credentials match `infrastructure/compose/.env.example` exactly:

| Service | Host Port | Container Port | Credentials / Config |
|---|---|---|---|
| **PostgreSQL** | `5432` | `5432` | `POSTGRES_USER=funds`<br>`POSTGRES_PASSWORD=funds_dev_pass`<br>`POSTGRES_DB=funds` |
| **Redis** | `6379` | `6379` | `REDIS_PASSWORD=funds_dev_pass` |
| **RabbitMQ AMQP** | `5672` | `5672` | `RABBITMQ_DEFAULT_USER=funds`<br>`RABBITMQ_DEFAULT_PASS=funds_dev_pass` |
| **RabbitMQ Management** | `15672` | `15672` | `RABBITMQ_DEFAULT_USER=funds`<br>`RABBITMQ_DEFAULT_PASS=funds_dev_pass` |
| **Redpanda Kafka API** | `9092` | `9092` | None (Dev Mode) |
| **Redpanda Admin API** | `9644` | `9644` | None (Dev Mode) |
| **Object store S3 API (SeaweedFS)** | `8333` | `8333` | `OBJECT_STORE_ROOT_ACCESS_KEY=funds`<br>`OBJECT_STORE_ROOT_SECRET_KEY=funds_dev_pass` |
| **Keycloak** | `8180` | `8080` | `KEYCLOAK_ADMIN=admin`<br>`KEYCLOAK_ADMIN_PASSWORD=admin_dev_pass` |
| **TigerBeetle** | `3000` | `3000` | `TIGERBEETLE_CLUSTER_ID=0`<br>`TIGERBEETLE_REPLICA_ID=0` |

---

### 4. Resetting the Stack

To completely reset all container states, Docker volumes, and host-persisted ledger/database files:

```bash
make clean FORCE=1 && make up
```

- `make clean` runs `docker compose down -v --remove-orphans` and deletes `infrastructure/compose/data`.
- If `FORCE=1` is omitted, `make clean` will prompt for confirmation before deleting data.
- On subsequent `make up`, TigerBeetle and PostgreSQL will format fresh data stores from scratch.

---

### 4a. Keeping Test Containers Warm (opt-in)

The Testcontainers fixtures in `libs/testing` declare `withReuse(true)`, but reuse
only takes effect if you opt in **per developer** — the file is outside the repo and
is deliberately not committed:

```bash
echo "testcontainers.reuse.enable=true" >> ~/.testcontainers.properties
```

With it enabled, fixture containers survive between builds instead of being torn
down by Ryuk, so `make verify` skips container startup on every run after the first.
Measured on this repo: ~30 s cold, ~26 s warm.

Two things to know:

- **Changing fixture configuration does not always start a fresh container.**
  Testcontainers matches on a hash of the container definition. If you edit a
  fixture and see stale behaviour, clear the containers and let them rebuild:

  ```bash
  make test-fixtures-clean
  ```

- **Reused containers keep their data**, and TigerBeetle cannot be reset at all
  (its `reset()` is a documented no-op). This is safe only because tests generate
  fresh random ids and assert only over ids they created — see the testing rules in
  `docs/ENGINEERING-RULES.md`. A test that totals a whole ledger id, table or topic will pass or
  fail based on what earlier runs left behind.

CI never reuses: the workflow sets `TESTCONTAINERS_REUSE_ENABLE=false` so a green
run proves the suite works from nothing. Reproduce that locally with:

```bash
make verify-cold
```

---

### 5. Troubleshooting Failure Symptoms

#### Symptom 1: Port Already in Use
- **Cause**: A local service (e.g. host-installed Postgres, Redis, or another container stack) is occupying a required port (e.g., `5432`, `6379`, or `3000`).
- **Error Example**: `Error starting userland proxy: listen tcp4 0.0.0.0:5432: bind: address already in use`
- **Fix**:
  1. Identify the process listening on the target port:
     ```bash
     lsof -i :5432
     ```
  2. Stop the host process (e.g. `brew services stop postgresql@17` or `kill -9 <PID>`).
  3. Alternatively, create an `infrastructure/compose/.env` file overriding the specific port variable (e.g. `POSTGRES_PORT=5433`).

#### Symptom 2: TigerBeetle Data File Mismatch After a Version Bump
- **Cause**: TigerBeetle image was upgraded, but existing `infrastructure/compose/data/tigerbeetle/0_0.tigerbeetle` file was formatted with an older binary version.
- **Error Example**: `panic: data file version mismatch` or `Format mismatch` on container start.
- **Fix**:
  1. Wipe local ledger data and volumes:
     ```bash
     make clean FORCE=1
     ```
  2. Re-up the stack so `tigerbeetle/entrypoint.sh` automatically formats a fresh data file matching the updated binary version:
     ```bash
     make up
     ```

#### Symptom 3: RabbitMQ Not Ready When Dependent Starts
- **Cause**: Application service attempts to connect to AMQP before RabbitMQ plugins and listener initialization are completed.
- **Error Example**: `Connection refused: localhost/127.0.0.1:5672` or AMQP socket error.
- **Fix**:
  1. Rely on `make up` which polls health checks (`rabbitmq-diagnostics -q ping`) before reporting ready.
  2. Check RabbitMQ logs if health check fails:
     ```bash
     make logs s=rabbitmq
     ```

#### Symptom 4: Out of Disk Space
- **Cause**: Docker disk image or host volume is full, causing database writes or image pulls to fail.
- **Error Example**: `no space left on device` or Redpanda storage error.
- **Fix**:
  1. Prune unused Docker containers, networks, images, and volumes:
     ```bash
     docker system prune -a --volumes
     ```
  2. Check available host disk space:
     ```bash
     df -h
     ```

#### Symptom 5: Docker Memory Limit Too Low for Stack
- **Cause**: Total stack memory reservations exceed the memory allocated to the Docker daemon, causing containers to be terminated by OOM Killer.
- **Error Example**: Container exits abruptly with status code `137` (`OOMKilled`).
- **Fix**:
  1. The container stack memory limits total ~6.5 GB RAM (TigerBeetle 1G, Postgres 1G, Redis 512M, RabbitMQ 1G, Redpanda 1.5G, object store 512M, Keycloak 1G).
  2. Open Docker Desktop -> **Settings** -> **Resources** -> **Memory** and set allocated RAM to at least **8 GB**.

<a id="skaffold-local-development"></a>

## Runbook: Skaffold Local Development

### 1. Overview

Skaffold coordinates the inner and outer development loops for the Fondaria platform on Kubernetes.
It leverages **Google Jib** to build OCI container images directly from Java byte code without requiring a local Docker daemon or writing custom Dockerfiles for each microservice.

Key components:
- **Build**: Jib Maven Plugin (`com.google.cloud.tools:jib-maven-plugin:3.4.4`).
- **File Sync**: Direct class and resource file synchronization into running containers for fast, sub-minute iterations.
- **Manifests**: Kustomize `infrastructure/k8s/overlays/local` overlay.
- **Port Forwarding**: Automatic local port mappings to API and storage services.

---

### 2. Prerequisites

1. **Tools** (Homebrew): `kind` >= 0.33, `kubectl` >= 1.31, `skaffold` v2.x, `kustomize` v5,
   Docker Desktop. Java 21 (Temurin) and Maven 3.9+ for the Jib builds.
2. **Docker VM memory: 8 GB is the floor, 12 GB is comfortable.** The cluster
   runs eight JVMs plus TigerBeetle (~1.2 GB even with the local grid-cache
   knob), Postgres, RabbitMQ, the object store (SeaweedFS), Valkey and the observability stack —
   about 4 GB resident. The Testcontainers fixtures `make verify` leaves
   running share the same VM and take another ~3 GB; on an 8 GB VM stop them
   (`make test-fixtures-clean`) before `make k8s-up`, or TigerBeetle is the
   first thing the kernel OOM-kills.
3. **The cluster itself** — one command, idempotent:
   ```bash
   make k8s-up
   ```
   `infrastructure/k8s/bootstrap/up.sh` creates the `fondaria` kind cluster
   from `kind-config.yaml` (Kubernetes 1.31, default CNI disabled, NodePorts
   30080–30082 and 30001 mapped to the host) and installs, from the upstream
   release manifests pinned in `versions.env`: Calico (so the platform's
   NetworkPolicies are enforced, not decorative), metrics-server (the CPU
   HPAs), CloudNativePG and the RabbitMQ cluster operator. It ends with the
   `fondaria` namespace set as the kubeconfig default. `make k8s-down` deletes
   the cluster.

Nothing else is needed locally: the local overlay generates its own dev
credentials (`overlays/local/kustomization.yaml`, same values as
`.env.example`), Postgres and RabbitMQ credentials come from the operators,
and `tigerbeetle-nvme` is mapped to kind's local-path provisioner. There is no
External Secrets Operator and no cert-manager in the local cluster.

**Known limits of the local cluster.** Jobs (`object-store-buckets`,
`rabbitmq-topology`) are immutable once created; if you change one, `kubectl
delete job <name>` before re-deploying. The compose stack and the cluster map
the same host ports, so run one at a time.

---

### 3. Daily Workflows

#### 3.1 Fast Inner Loop: `skaffold dev`
To launch continuous file watch, automated Jib building, deployment to the local overlay, log streaming, and port forwarding:
```bash
make skaffold-dev
```
Or directly via the CLI:
```bash
skaffold dev --kube-context kind-fondaria --port-forward
```

**Iteration and Rebuild Mechanics:**
- When Java source files or resource files in `services/<service>/src/main` change, Skaffold triggers Jib's fast incremental layer compilation.
- In-place file sync (`sync.auto: true`) maps modified class files and resources. Due to security hardening (`readOnlyRootFilesystem: true` and shell-less/tar-less distroless containers), in-pod runtime file mutation is restricted, so Skaffold falls back to Jib's rapid incremental OCI image building (~6.5s) followed by a rolling restart (~10-15s). The entire cycle completes in under 25 seconds, well within the 60-second limit.
- Container layers (dependencies, runtime configuration, and application classes) are cached, so unchanged libraries are never re-downloaded or re-packaged.

#### 3.2 Single-Pass Deploy: `skaffold run`
To build and deploy the current repository state once to the local cluster:
```bash
make skaffold-run
```

#### 3.3 Teardown and Cleanup: `skaffold delete`
To delete all deployed deployments, statefulsets, services, and associated objects:
```bash
make skaffold-delete
```
This cleans up all deployed Kubernetes workloads leaving no orphan deployment residue. (Note: PersistentVolumeClaims generated by StatefulSets are retained by Kubernetes default design to safeguard local test data).

---

### 4. Port Forwarding Reference

When running with `--port-forward` (default in `make skaffold-dev`), the following local ports are mapped:

| Service | Remote Port (Service) | Local Port | Purpose |
|---|---|---|---|
| `contribution-api` | 80 | `30080` | Contribution ingestion API |
| `balance-api` | 80 | `30081` | Query and balance API |
| `valuation-api` | 80 | `30082` | Valuation ingestion API |
| `tigerbeetle` | 3000 | `3000` | Direct TigerBeetle ledger access |
| `valkey` | 6379 | `6379` | Cache cluster client port |

---

### 5. Troubleshooting

- **Image Push Errors**: Skaffold is configured with `build.local.push: false` to avoid pushing images to external registries. Ensure your local cluster has access to local image caches or is running inside the same container daemon.
- **Port Conflict with Compose**: If a local port (e.g. 30080 or 3000) is already bound by a local Docker Compose stack (`make up`), stop the compose services (`make down`) before launching `make skaffold-dev`. The kind cluster maps 30080–30082 and 30001 to the host itself (`bootstrap/kind-config.yaml`), so the three APIs and Grafana are reachable without a port-forward.
- **`ImagePullBackOff` on `fondaria/*`**: the base manifests name `fondaria/<service>:0.1.0-SNAPSHOT`, which exists only once Skaffold has built and loaded it. Deploy through `make skaffold-run` / `make skaffold-dev`, never `kubectl apply -k` alone.
- **TigerBeetle or a service exits with `io_uring is not available` / `failed to initialize IO: PermissionDenied`**: the pod is missing its `seccompProfile: Unconfined` (base manifests carry it for every workload that links the TigerBeetle client); the runtime's default seccomp profile blocks io_uring.
- **Only TigerBeetle keeps restarting, `OOMKilled`**: the Docker VM is out of memory — see Prerequisites.
- **A pod sits in `Init:N/M`, or `Init:Error` / `Init:CrashLoopBackOff`**: it is waiting for a dependency (ADR-0037). The container name says which: `kubectl logs <pod> -c wait-for-postgres` (or `-rabbitmq`, `-valkey`, `-object-store`, `-tigerbeetle`) prints what it is waiting for, once every 3 s; after 15 minutes it exits non-zero and the kubelet retries it. `amqp-fanout` waiting on `projection.*` bindings means `projection-worker` has not connected yet; `wait-for-object-store` waiting on a bucket means the `object-store-buckets` Job has not finished (`kubectl logs job/object-store-buckets`). Every check in a meshed pod goes through its Linkerd proxy: if the pod's init containers do not list `linkerd-proxy` before the `wait-for-*` ones, the installed Linkerd did not honour the native-sidecar annotation, and every wait times out.
- **`amqp-fanout` or `projection-worker` is `Running` but not Ready**: a RabbitMQ listener container has stopped for good (a fatal consumer error, for instance refused credentials), and readiness reports it rather than hide it (ADR-0037). The pod's log names the cause; fix it and `kubectl rollout restart deployment/<name>`.
- **Fast Clean Compilation**: If class dependencies become misaligned, trigger `mvn clean compile -pl services/<service> -am`.

<a id="gitops-and-canary-rollouts"></a>

## Runbook: GitOps and Canary Rollouts

### Overview

Continuous delivery for Fondaria is managed declaratively through **ArgoCD** and **Argo Rollouts**:
1. **ArgoCD Applications**:
   - `fondaria-local`: Targets `infrastructure/k8s/overlays/local` with automated reconciliation (`automated.prune: true`, `automated.selfHeal: true`).
   - `fondaria-prod`: Targets `infrastructure/k8s/overlays/prod` with **manual sync approval** (no automated sync block). Changes require explicit engineer gate approval.
2. **Sync Waves (`argocd.argoproj.io/sync-wave`)**:
   - **Wave 0**: PriorityClasses, operators (`cloudnative-pg`, `rabbitmq-operator`), certificates, external secrets.
   - **Wave 1**: Mission-critical storage & infrastructure (TigerBeetle, CloudNativePG PostgreSQL, RabbitMQ cluster, Valkey).
   - **Wave 2**: Stateless background workers & jobs (`amqp-fanout`, `projection-worker`, `allotment-job`, `statement-worker`, `eod-job`, `tigerbeetle-cdc`).
   - **Wave 3**: Public APIs & edge ingress (`contribution-api`, `balance-api`, `valuation-api`).
3. **Argo Rollouts Progressive Delivery**:
   - Canary rollout for `contribution-api` (`payment-api`).
   - Automated Prometheus `AnalysisTemplate` monitoring `transfer_rejected_total` rejection rate (< 2%) and p99 latency SLO (< 250ms).

---

### 1. ArgoCD Sync Waves & Dependency Order

ArgoCD processes resources strictly in increasing wave number. A wave will not begin applying until all health checks in the previous wave report `Healthy`.

```
Wave 0: Operators, PriorityClasses, Secrets
              │
              ▼
Wave 1: Storage (TigerBeetle, Postgres, RabbitMQ, Valkey)
              │
              ▼
Wave 2: Streaming & Background Workers (AMQP fanout, projection-worker, CDC)
              │
              ▼
Wave 3: External APIs & Ingress (contribution-api, balance-api, valuation-api)
```

#### Investigating Stalled Sync Waves

If an ArgoCD sync hangs on a wave:
1. Inspect the Application status:
   ```bash
   argocd app get fondaria-prod
   ```
2. Identify resources in the current wave that are `Degraded` or `Progressing`:
   ```bash
   kubectl get pods -n fondaria -l app.kubernetes.io/part-of=fondaria
   ```
3. Check pod events and logs for initialization errors (e.g. database migration or volume mount delays).

---

### 2. Production Promotion & Manual Sync Workflow

Production deployments are gated and do not auto-sync upon commit.

#### Promotion Steps:
1. Review manifest diffs against the live cluster:
   ```bash
   argocd app diff fondaria-prod
   ```
2. Initiate manual synchronization:
   ```bash
   argocd app sync fondaria-prod --prune
   ```
3. Monitor sync wave progression:
   ```bash
   argocd app wait fondaria-prod --health --timeout 600
   ```

---

### 3. Canary Progressive Delivery & Analysis Failure Triage

`contribution-api` uses Argo Rollouts to progressively shift traffic while running real-time metric analysis:
1. Shift 20% traffic to canary pods.
2. Pause 2 minutes while `AnalysisTemplate` evaluates Prometheus metrics.
3. If healthy, shift 50% traffic to canary pods and pause 5 minutes.
4. If healthy, promote to 100% stable revision.

#### Automated Rollback Triggers:
- **Transfer Rejection Rate (`transfer-rejection-rate`)**:
  If the proportion of non-idempotent rejections exceeds 2% over 2 minutes, the rollout aborts immediately.
- **Payment Latency p99 (`payment-latency-p99`)**:
  If p99 request latency exceeds 250ms (the financial SLO), the rollout aborts immediately.

#### Inspecting a Failed / Aborted Canary:
1. Check rollout status:
   ```bash
   kubectl argo rollouts get rollout contribution-api -n fondaria
   ```
2. View AnalysisRun results:
   ```bash
   kubectl get analysisrun -n fondaria
   kubectl describe analysisrun <analysisrun-name> -n fondaria
   ```
3. Check canary pod logs for unexpected error codes or thread contention:
   ```bash
   kubectl logs -n fondaria -l app.kubernetes.io/name=contribution-api,role=canary --tail=100
   ```
4. Retry or promote manually (only if false positive is established):
   ```bash
   kubectl argo rollouts retry rollout contribution-api -n fondaria
   ```

---

### 4. Configuration Drift & Self-Heal

In the non-production (`local`) environment, ArgoCD self-heal is active. Any out-of-band modifications made directly via `kubectl edit` or `kubectl patch` are automatically reverted back to the committed Git state within seconds.

#### In Production:
Production self-heal is intentionally disabled to avoid flapping during emergency operational overrides.
- To identify drift in production:
  ```bash
  argocd app diff fondaria-prod
  ```
- To reconcile drift back to Git declaration:
  ```bash
  argocd app sync fondaria-prod
  ```

---

# Service-level objectives

<a id="slo-availability"></a>

## Runbook — platform availability SLO breach

**Alert:** `PlatformAvailabilityBudgetBurn` (`infrastructure/observability/alerts/financial-control.rules.yml`).
**When to use this.** HTTP 5xx error rate exceeds 0.05% over a 5-minute window (`rate(http_server_requests_milliseconds_count{status=~"5.."}[5m]) / rate(http_server_requests_milliseconds_count[5m]) > 0.0005`), breaching the 99.95% platform availability SLO (`severity: page`).

### Symptoms

- Page fires (`severity: page`, `for: 5m`).
- Internal or external API clients receive HTTP 500, 502, 503, or 504 responses.
- Platform availability error budget is actively draining.

### What is at risk

**Core service availability and business disruption.** High 5xx rates block employers from submitting contributions, participants from managing savings, and automated jobs from running.

### First three diagnostic commands

1. **Identify which service is returning 5xx errors:**
   ```sql
   -- Or via Prometheus:
   -- sum by (job, status) (rate(http_server_requests_milliseconds_count{status=~"5.."}[5m]))
   ```

2. **Check recent application error logs:**
   ```bash
   # Kubernetes / Production:
   kubectl -n core logs deployment/contribution-api --tail=200 | grep -E "ERROR|Exception"
   kubectl -n core logs deployment/balance-api --tail=200 | grep -E "ERROR|Exception"

   # Loki / Log query:
   # {status=~"5.."} |= "Exception"
   ```

3. **Check health of foundational dependencies:**
   - TigerBeetle (`docker compose -f infrastructure/compose/docker-compose.yml ps tigerbeetle`)
   - PostgreSQL (`docker compose -f infrastructure/compose/docker-compose.yml exec postgres pg_isready`)
   - Redis (`docker compose -f infrastructure/compose/docker-compose.yml exec redis redis-cli ping`)
   - Redpanda (`docker compose -f infrastructure/compose/docker-compose.yml exec redpanda rpk cluster health`)

### Decision criteria

| Finding | Action |
|---|---|
| Service unable to connect to database or cache | Restore connectivity or failover to database replica. |
| Bad deployment / regression introduced | Execute immediate rollback of the offending service container/image. |
| Infrastructure resource starvation (OOMKilled pods) | Increase memory/CPU limits and scale service instances. |

### Escalation

- Immediate page to Platform Engineering Incident Commander and On-Call SRE.

<a id="slo-balance-latency"></a>

## Runbook — balance read latency SLO breach

**Alert:** `BalanceReadLatencyBudgetBurn` (`infrastructure/observability/alerts/financial-control.rules.yml`).
**When to use this.** More than 1% of balance read requests (`/v1/participants/{participantId}/balance`) exceed the 50ms SLO threshold for at least 5 minutes (`severity: warning`).

### Symptoms

- Warning alert fires.
- Participant portal and mobile app users experience sluggish balance loads and statement lookups.
- Error budget for balance read latency is burning.

### What is at risk

**User experience and participant satisfaction.** While money is completely safe, read degradation causes customer complaints and perceived platform unreliability.

### First three diagnostic commands

1. **Check Redis cache hit ratio:**
   Inspect Redis cache hit vs miss counters for `balance-api` to determine if hot accounts or NAV lookups are missing the cache and hitting the database.

2. **Inspect database read queries on `projection.participant_units`:**
   ```sql
   SELECT query, calls, total_exec_time, mean_exec_time
   FROM pg_stat_statements
   WHERE query LIKE '%participant_units%'
   ORDER BY mean_exec_time DESC
   LIMIT 5;
   ```

3. **Check `balance-api` pod resource limits:**
   Check CPU throttling and memory utilization on `balance-api` instances.

### Decision criteria

| Finding | Action |
|---|---|
| Redis down or cache evictions high | Verify Redis cluster memory limits and restart/scale Redis cache pods. |
| Missing index or sequential scan on `projection` tables | Analyze query plan and ensure indexes on `(tenant_id, participant_id)` and `(account_id)` are healthy. |
| Pod CPU saturated due to JSON serialization / virtual thread count | Scale `balance-api` replica count. |

### Escalation

- If latency continues to degrade above 200ms, page the API services team.

<a id="slo-payment-latency"></a>

## Runbook — payment initiation latency SLO breach

**Alert:** `PaymentInitiationLatencyBudgetBurn` (`infrastructure/observability/alerts/financial-control.rules.yml`).
**When to use this.** More than 1% of payment initiation requests (`/v1/organizations/{organizationId}/cash-receipts`) exceed the 250ms SLO threshold for at least 5 minutes (`severity: page`).

### Symptoms

- Page fires (`severity: page`, `for: 5m`).
- Clients experience slow HTTP responses when submitting contribution batches or payment instructions.
- Error budget for payment latency is depleting rapidly.

### What is at risk

**SLA breach with enterprise employers and payroll processing delays.** Employers submitting salary deduction files during payroll cutoff windows may experience timeouts and delayed allocations.

### First three diagnostic commands

1. **Check latency distribution and throughput in Tempo / Prometheus:**
   Query Tempo traces for `service.name = "contribution-api"` with duration > 250ms to see which span spans the majority of time (TigerBeetle ledger call, PostgreSQL master write, or validation).

2. **Check TigerBeetle response times and connection health:**
   Inspect `libs/ledger-tigerbeetle` client logs or metrics to verify if TigerBeetle write batches are experiencing latency.

3. **Check PostgreSQL connection pool saturation and CPU utilization:**
   ```sql
   SELECT count(*), state, wait_event_type, wait_event
   FROM pg_stat_activity
   GROUP BY state, wait_event_type, wait_event;
   ```

### Decision criteria

| Finding | Action |
|---|---|
| PostgreSQL database pool exhausted or high lock waits | Scale connection pool or kill long-running read transactions blocking master tables. |
| TigerBeetle cluster latency spike | Verify TigerBeetle replica health, disk IOPS, and network latency between nodes. |
| Ingress traffic spike (e.g. month-end payroll submission surge) | Verify Horizontal Pod Autoscaler (HPA) is scaling `contribution-api` replicas to absorb the load. |

### Escalation

- Escalate to on-call Platform SRE and Database Administrator if latency remains > 500ms for more than 15 minutes.

<a id="slo-cdc-lag"></a>

## Runbook — CDC lag SLO breach

**Alert:** `CDCLagSLOBudgetBurn` (`infrastructure/observability/alerts/financial-control.rules.yml`).
**When to use this.** `max(ledger_cdc_lag_seconds) > 5` (the worst projection-worker pod; each replica exports its own gauge, ADR-0032 §4) while queue has pending messages (`sum(rabbitmq_detailed_queue_messages{queue=~"projection\\..*"}) > 0`) for at least 5 minutes (`severity: warning`).

Also the runbook for `ProjectionQueueNotDraining` (warning): a `projection.*` queue held messages and did not shrink for 10 minutes. A stuck consumer drops out of the lag head after the checkpoint active window, so queue depth is the signal that catches it. Check `projection_halted` first, then the points below.

### Symptoms

- Warning alert fires.
- CDC lag exceeds the 5-second p99 SLO target continuously while events are pending.
- Read-side views in PostgreSQL lag behind ledger activity by more than 5 seconds.

### What is at risk

**Read-after-write consistency.** Participants who just executed a contribution or trade may not see their new balance immediately upon page refresh, causing re-submissions or confusion.

### First three diagnostic commands

0. **Read each worker's activity line (ADR-0035).** Every projection-worker pod logs one INFO line a minute:
   ```bash
   kubectl -n fondaria logs deploy/projection-worker --since=3m | grep "projection activity"
   ```
   `ready [...]` is each queue's backlog from the broker, and `consumed [...]` is what this pod committed in the window. A queue under `not draining [...]` holds messages that this pod consumes from and committed none of: that pod is the one to look at. `entries queues owned N/16` summed across pods should be 16. Ownership hand-overs are logged as `acquired entries queue` / `released entries queue`. On the dashboard, the "Projection Queue Backlog", "Messages Committed per Second" and "Entries Queue Owners" panels show the same data over time.

1. **Check consumer concurrency and queue depth:**
   ```bash
   # Depth and consumer count per projection queue (the transfers queue has PROJECTION_TRANSFER_CONSUMERS x replicas consumers; each entries queue has exactly one active exclusive consumer, ADR-0034 amendment)
   kubectl -n fondaria exec rabbitmq-server-0 -c rabbitmq -- rabbitmqctl list_queues name messages consumers | grep projection
   ```
   One entries queue lagging while the others drain usually means its single owner is retrying a batch that keeps failing (ADR-0034 amendment): find `transient failure projecting a batch ... from queue <name>` in the projection-worker logs. Its standby replicas cannot take that queue over while the owner retries, so restarting the owner pod only moves the failure. The broker's `access_refused ... in exclusive use` errors are the standbys, and are expected.
   Then confirm the pool is not the bottleneck: listener threads wait for a connection, not a row lock. `POSTGRES_MAX_POOL_SIZE` per pod (configmap) times replicas must stay under CNPG `max_connections` (200) minus the other services' pools.

2. **Inspect projection worker database throughput:**
   Verify batch upsert rates and database transaction latencies on `projection.transfer` and `projection.participant_units`.

3. **Check for serialization conflicts or lock contention:**
   Verify if concurrent updates to holding accounts for the same organization are queuing up behind database row locks.

### Decision criteria

| Finding | Action |
|---|---|
| Large batch processing in progress (e.g. 10,000 participant payroll file) | Normal transient surge. Observe lag falling as worker drains partitions. |
| Database write throughput low | Inspect PostgreSQL I/O metrics, checkpoint spikes, and disk write latency. |
| Consumer thread pool starved | Adjust `fondaria.projection.concurrency` configuration if safe per partition bounds. |

### Escalation

- If CDC lag continues rising past 30 seconds, `CDCLagWarning` will trigger. Follow ``#cdc-replay`` if the pipeline stalls completely.

---

# Platform health

<a id="operational-health-dashboard"></a>

## Runbook — operational health dashboard

**Alerts:** `WorkloadBelowDesiredReplicas`, `PostgresInstanceNotReady`, `PodCrashLooping`, `SetupJobFailed`, `SetupJobRetrying`, `QueueHasMessagesButNoConsumers`, `CriticalQueueHasNoConsumer`, `KubeStateMetricsDown` (`infrastructure/observability/alerts/operational-health.rules.yml`).
**When to use this.** After every deploy to a cluster, and whenever one of those alerts fires. The Grafana dashboard **Operational Health** (uid `operational-health`, `infrastructure/observability/dashboards/operational-health.json`) answers "is every pod, Job, datastore and consumer doing its job?" without `kubectl`. It is Kubernetes-only: under compose its panels are empty, because the `kube_*` series come from kube-state-metrics (`infrastructure/k8s/base/observability/kube-state-metrics/`), which compose does not run.

### Opening it

- kind: Grafana is on `http://localhost:30001` (NodePort).
- GKE: `kubectl -n fondaria port-forward svc/grafana 3000:3000`, then `http://localhost:3000`, dashboard **Operational Health**.

Every panel's description (the ⓘ next to its title) says what "bad" looks like for that panel.

### Reading it, top to bottom

1. **Top row: one number per failure class.** Workloads below desired replicas, pods running but not Ready, Pending pods, CrashLoopBackOff containers, restarts and OOMKills in the time range. Healthy is 0 across the row.
2. **Second row: one tile per datastore and dependency.** Nodes Ready, failed setup Jobs, Postgres instances Ready, RabbitMQ nodes, TigerBeetle replicas, scrape targets down. Datastore tiles go green at 3 (the small GKE profile; TigerBeetle has 6 in production).
3. **Replicas, then pods.** The Deployments and StatefulSets tables name the workload that is short. *Containers waiting, by reason* and *Last termination reason* say why: `ImagePullBackOff`, `CreateContainerConfigError` (a missing Secret or ConfigMap key), `OOMKilled`, `Error`.
4. **Setup Jobs.** `rabbitmq-topology`, `object-store-buckets` and CloudNativePG's `postgres-N-initdb` / `postgres-N-join`. Failed attempts while Succeeded stays 0 mean the Job is waiting on a dependency that has not formed. Jobs are deleted an hour after they finish, so read their logs before then.
5. **Postgres.** Replication lag per standby, and per instance: up, in recovery (exactly one instance at 0 is the primary), streaming standbys.
6. **RabbitMQ.** Depth and consumers for `bridge.ledger`, every `projection.entries.v1.*` partition and every DLQ, and a table of queues holding messages with no consumer. **Read the consumer count, not pod readiness:** Spring's `/actuator/health/readiness` does not include the AMQP listener, so a pod can be Ready with its consumer dead. That is the failure the 2026-10-26 GKE run found only by hand.
7. **Service readiness.** The share of each Spring service's containers that are Ready.

### First three diagnostic commands

Only when the dashboard has already named the pod or Job:

```bash
kubectl -n fondaria describe pod <pod>                    # events: scheduling, probes, OOMKilled
kubectl -n fondaria logs <pod> -c <container> --previous  # the crashed run, not the restarted one
kubectl -n fondaria logs job/<job-name>                   # a setup Job's last attempt
```

### Decision criteria

- **Queue with messages and no consumer** (`QueueHasMessagesButNoConsumers`, page): restart the consumer, `amqp-fanout` for `bridge.ledger` and `projection-worker` for `projection.entries.v1.*` (`kubectl -n fondaria rollout restart deployment/<name>`), then confirm on the dashboard that the consumer count comes back and the depth drains. Every consumer is idempotent on `transfer_id`, so redelivered messages are safe.
- **A setup Job failed** (`SetupJobFailed`): fix what it waited on (usually the RabbitMQ cluster or object store not formed), then delete the Job and re-apply the overlay so it runs again. Both setup Jobs are idempotent.
- **Postgres instance not Ready** for more than 5 minutes while its `postgres-N-join` Job is active: it is still copying from the primary (`pg_basebackup`). Watch its replication lag fall before acting. If the join Job is failing, follow [PostgreSQL Primary Failover and PITR](#postgres-failover-and-pitr).
- **`KubeStateMetricsDown`**: every pod, replica and Job panel is blind, and so are the alerts above. Restore kube-state-metrics before trusting an empty dashboard.

### Escalation

- A StatefulSet datastore (TigerBeetle, RabbitMQ, Valkey) down to fewer than a majority of replicas Ready: treat as an outage of that datastore and follow its own runbook ([TigerBeetle](#tigerbeetle-upgrades), [RabbitMQ](#rabbitmq-node-failure)).

---

# Ledger, CDC and projection

<a id="cdc-replay"></a>

## Runbook — replaying the CDC stream and rebuilding the projection

**When to use this.** The `projection` schema — `projection.transfer` and
`projection.participant_units` — is wrong, incomplete, or suspect, and you need to
rebuild it from the ledger. Typical triggers:

- the CDC-lag page fired (`ledger_cdc_lag_seconds` > 300s) and the consumer is
  **stalled**, not merely idle — events are waiting and the checkpoint is not moving
  (Task 11.2 tells the two apart using queue depth vs idle pipeline discrimination);
- a projection bug shipped, was fixed, and the rows written under the bug have to go;
- `make rebuild-projection` (nightly CI) failed its exact-match assertion, meaning a
  replay does **not** reproduce the current state — investigate before rebuilding
  anything in an environment that matters.

**Why this is safe.** The projection is a cache of the ledger (`ADR-0008`). A balance
is never read from it. Every row in it is a pure function of the CDC change events, and
the consumer is idempotent — `projection.transfer` inserts `ON CONFLICT DO NOTHING`,
`projection.participant_units` upserts only when the incoming ledger timestamp is newer
(Task 5.5). So replaying the whole log from timestamp 0 converges to exactly the state a
clean build would produce. `make rebuild-projection` is the automated proof of that
property; this runbook is the manual procedure that relies on it.

**When NOT to use this.** If the consumer is **idle** — no new ledger movements, lag
growing naturally — there is nothing to fix; lag shrinks on its own when movement
resumes. If the checkpoint is advancing but slowly, you have a throughput problem, not a
correctness one: check `projection_rebuild_transfers_per_second` from the last nightly
run for the expected rate, and look at Postgres before touching the stream.

---

### 0. Establish where the pipeline is

Three signals matter. Read all three before doing anything.

| Signal | Where | Meaning |
|---|---|---|
| `ledger_head_timestamp` | Task 11.2 metric | newest ledger timestamp the CDC job has published to RabbitMQ |
| projection head | the query below | the slowest active consumer's committed ledger timestamp |
| queue depth | `rabbitmqadmin list queues name messages` or Grafana DLQ / Queue panel | messages in `projection.transfers.v1` and `projection.entries.v1.*` |

The projection head (`ADR-0032` §1) is read from one checkpoint row per AMQP consumer. It is the
lowest timestamp among consumers that committed in the last 5 minutes. A consumer idle longer than
that is caught up, not behind, and is left out. When every consumer is idle, the head is the highest
row:

```sql
SELECT COALESCE(
  (SELECT min(last_ledger_timestamp) FROM projection.projection_consumer_checkpoint
    WHERE projection_name = 'projection-worker'
      AND updated_at > now() - interval '5 minutes'),   -- PROJECTION_CHECKPOINT_ACTIVE_WINDOW
  (SELECT max(last_ledger_timestamp) FROM projection.projection_consumer_checkpoint
    WHERE projection_name = 'projection-worker')) AS projection_head;
```

The head is a **monitoring estimate, not a gap-free watermark**: with several consumers on one
queue, an older delivery can still be in flight below it. Nothing replays from it; replay is §1.
To see which consumer is behind:

```sql
SELECT consumer_id, queue_name, last_ledger_timestamp, updated_at
FROM projection.projection_consumer_checkpoint
WHERE projection_name = 'projection-worker'
ORDER BY last_ledger_timestamp;
```

`consumer_id` is `<pod>/<AMQP consumer tag>`. Rows idle for over an hour are deleted
automatically, except the row holding the highest timestamp, so after the first event the head is
never empty. An **empty head** (NULL) means nothing has been committed since the last reset (§1
step 3): check readiness, the halt and queue depth before concluding anything. `projection.projection_checkpoint` (a single row) is no longer written and is kept
only until a later migration drops it; do not read it.

- **projection head ≈ ledger head, queue depth ≈ 0** → the projection is current. If it still
  looks wrong, the problem is a past bug, not lag — go to §1 (rebuild from ledger).
- **projection head < ledger head, queue depth > 0, queue depth falling** → it is catching up. Watch;
  do not intervene.
- **projection head < ledger head, queue depth > 0, queue depth flat or rising, checkpoint not moving**
  → stalled. Check `/actuator/health/readiness` on the worker: `DOWN` with a halt means
  a poison message (see §2). Otherwise it is a transient failure retrying forever
  (Postgres unreachable, disk full) — fix that cause and the consumer resumes on its
  own. No replay needed.

---

### 1. Rebuild the projection from the ledger (`--timestamp-last=0`)

Under ADR-0022, RabbitMQ is the single broker. RabbitMQ queues hold unconsumed messages
only; consumed messages are acknowledged and removed rather than retained on the broker.
**Replay always comes from the ledger via TigerBeetle's native CDC connector** (`phase-04-cdc.md`,
`ADR-0002:8`).

#### Procedure

1. **Stop `projection-worker`**:
   ```bash
   kubectl -n <ns> scale deployment/projection-worker --replicas=0
   ```
   (In local development: stop the `projection-worker` process.)

2. **Purge existing queue messages (if any)**:
   Clear any partially-processed messages from the projection queues so the replay starts cleanly:
   ```bash
   rabbitmqadmin purge queue name=bridge.ledger
   rabbitmqadmin purge queue name=projection.transfers.v1
   for i in $(seq 0 15); do
     rabbitmqadmin purge queue name=projection.entries.v1.$i
   done
   ```

3. **Truncate projection tables and reset the checkpoint**:
   The projection movement tables are append-only to every runtime role (V21); `TRUNCATE` is
   reserved for the **table owner / a superuser** and is the rebuild path:
   ```sql
   -- as the migration/owner role, NOT fondaria_cdc or fondaria_app
   TRUNCATE projection.transfer, projection.participant_units;
   DELETE FROM projection.projection_consumer_checkpoint WHERE projection_name = 'projection-worker';
   ```

4. **Trigger CDC replay from timestamp 0**:
   Run the CDC job with `CDC_TIMESTAMP_LAST=0`:
   - In Kubernetes: update the CDC Deployment/Job environment `CDC_TIMESTAMP_LAST=0` and restart:
     ```bash
     kubectl -n <ns> set env deployment/tigerbeetle-cdc CDC_TIMESTAMP_LAST=0
     kubectl -n <ns> rollout restart deployment/tigerbeetle-cdc
     ```
   - In Docker Compose:
     ```bash
     CDC_TIMESTAMP_LAST=0 docker compose --profile infra up -d cdc-job
     ```
   The connector invokes `tigerbeetle amqp ... --timestamp-last=0` and streams the entire ledger
   history onto the `tigerbeetle` exchange. `services/amqp-fanout` consumes `bridge.ledger` and
   publishes the exploded transfers and entries into `ledger.transfers.v1` and `ledger.entries.v1`.

5. **Start `projection-worker`**:
   ```bash
   kubectl -n <ns> scale deployment/projection-worker --replicas=<n>
   ```
   The worker consumes from the consistent-hash queues and transfer queue, rebuilding
   `projection.transfer` and `projection.participant_units` idempotently.

6. **Restore CDC configuration**:
   Once the replay has completed and `ledger_head_timestamp` matches the current ledger, unset
   or clear `CDC_TIMESTAMP_LAST` so future restarts resume from the progress queue:
   ```bash
   kubectl -n <ns> set env deployment/tigerbeetle-cdc CDC_TIMESTAMP_LAST=""
   # Or in Docker Compose:
   CDC_TIMESTAMP_LAST="" docker compose --profile infra up -d cdc-job
   ```

---

### 2. If a poison message is blocking the stream

A poison message halts the **whole** worker, not one pod (`ADR-0032` §2). The pod that met
it records a row in `workflow.projection_halt` and every `projection-worker` replica reads it
within `PROJECTION_HALT_POLL_INTERVAL` (default 1 s), stops consuming and reports
`/actuator/health/readiness` = `DOWN`. `projection_poison_total` incremented and **one or more**
records are on `ledger.entries.dlq` (a replica that received the redelivered poison before it
saw the flag dead-letters its own copy). It never skips: the gap is closed deliberately.

0. Confirm the halt and who raised it:
   ```sql
   SELECT halt_id, halted_at, halted_by, reason_code, source_queue
   FROM workflow.projection_halt
   WHERE projection_name = 'projection-worker' AND cleared_at IS NULL;
   ```
   The row carries no payload. The full reason (queue, exchange, decode failure) is in
   `halted_by`'s log; `projection_halted` is 1 on every replica.
1. Inspect the dead-lettered message from `ledger.entries.dlq`:
   ```bash
   rabbitmqadmin get queue=ledger.entries.dlq count=1 requeue=false
   ```
   and check the halt reason in the worker log (routing key, payload decode failure kind).
2. Decide: is the message genuinely malformed (a bug in `amqp-fanout` or an unknown schema
   version), or is the consumer's decoder wrong?
   - **Decoder wrong** → fix and deploy `projection-worker`. On restart it re-reads the
     unacknowledged message and proceeds.
   - **Message genuinely bad** → it must be corrected at the source (the ledger record
     is right; the CDC payload is not).
3. **Clear the halt**, recording who and why, run as the owner role (a worker's role can raise
   a halt but never clear one):
   ```sql
   UPDATE workflow.projection_halt
   SET cleared_at = now(), cleared_by = '<who>', clear_note = '<ticket / what was fixed>'
   WHERE projection_name = 'projection-worker' AND cleared_at IS NULL;
   ```
   Then restart every replica. Halted pods stay halted until restarted, even after the row is
   cleared; a pod that starts while the row is still active halts itself again.
   ```bash
   kubectl -n <ns> rollout restart deployment/projection-worker
   ```
4. Confirm readiness returns to `UP` on every replica and the checkpoint advances past the
   poison message. If the fix was wrong, the worker halts again and raises a new row; the
   cleared row stays as the record of the earlier incident.

---

### 3. What "done" looks like

- Queue depth in `projection.transfers.v1` and `projection.entries.v1.*` equals 0.
- projection head (the §0 query) equals `ledger_head_timestamp`.
- `ledger_cdc_lag_seconds` back under the warn threshold (30s).
- Spot-check a handful of participants whose balances you know:
  `units(as-of today) × NAV(today)` from `projection.participant_units` +
  `reference.fund_nav` matches `LedgerPort.lookupAccount` for the same holding accounts.
  The ledger is the authority; the projection has to agree with it, not the other way
  round.
- If you rebuilt in a lower environment, run `make rebuild-projection` there afterward:
  a green exact-match assertion confirms the rebuilt state is itself reproducible.

---

### Why the nightly job exists

`make rebuild-projection` (`.github/workflows/nightly-rebuild.yml`) does §1 end to end
against ~100,000 change events every night and fails if the rebuilt state is not
byte-identical to the original. It also records
`projection_rebuild_transfers_per_second`. Two things break loudly there before they
ever bite in production: a projection write that is not idempotent or is order
sensitive (the exact-match assertion fails), and a throughput regression that would make
a real rebuild take too long (the rate drops). Check the last successful run's rate
before starting a large manual rebuild so you know how long to expect it to take.

<a id="dead-letter-queue"></a>

## Runbook — dead-letter queue messages present

**Alert:** `DLQMessagesPresent` (`infrastructure/observability/alerts/financial-control.rules.yml`).
**When to use this.** `dlq_depth > 0`: messages have been rejected by consumer pipelines and placed onto a dead-letter queue, or `projection_poison_total` has incremented.

### Symptoms

- Page fires immediately (`severity: page`, `for: 0m`).
- `dlq_depth` is greater than zero on the Financial Control Dashboard.
- One or more CDC consumers or workers may have halted per the "never skip a message, halt instead" policy (`docs/ENGINEERING-RULES.md` §4).

### What is at risk

**Completeness of the financial event record.** A message routed to the DLQ represents a financial transaction or accounting event that could not be processed. This means the event has not been projected into PostgreSQL, or downstream notifications have failed.

### First three diagnostic commands

1. **Check DLQ depth across queues:**
   ```bash
   # In RabbitMQ: check messages in DLQ queues
   docker compose -f infrastructure/compose/docker-compose.yml exec rabbitmq rabbitmqctl list_queues name messages | grep -i dlq
   ```

2. **Inspect the dead-lettered message headers and error envelope:**
   ```bash
   # Kubernetes / Production:
   kubectl -n core logs deployment/projection-worker --tail=200 | grep -i "poison"
   kubectl -n core logs deployment/amqp-redpanda-bridge --tail=200 | grep -i "dlq"

   # Local development / Compose:
   docker compose -f infrastructure/compose/docker-compose.yml logs --tail=200 amqp-redpanda-bridge | grep -i "dlq"
   ```

3. **Identify the root exception reason:**
   Look for `schemaVersion` mismatch (`UnknownLedgerEventVersionException`), unparseable numbers, negative cash amounts, or JSON deserialization failures.

### Decision criteria

| Finding | Action |
|---|---|
| Schema version mismatch (`UnknownLedgerEventVersionException`) | A producer deployed an unsupported schema version before downstream consumers were updated. Roll forward consumers or rollback producer. |
| Malformed payload (corrupt JSON or truncated bytes) | Inspect network or broker corruption. Isolate message body, verify transaction in TigerBeetle via `transfer_id`, and craft a targeted replay. |
| Database constraint or transient dependency error | If message was rejected due to a database deadlock or timeout, replay from DLQ once connectivity is stable. |

### If `projection-worker` has halted

A poison message halts **every** `projection-worker` replica through a shared flag, not just the
pod that met it (`ADR-0032` §2). Readiness is `DOWN` and `projection_halted` is 1 on all of them.

1. See the active halt: `SELECT * FROM workflow.projection_halt WHERE cleared_at IS NULL;`
2. Fix the cause (table above), then clear the halt and restart the Deployment: the exact
   statement and `kubectl rollout restart` step are in ``#cdc-replay`` §2.

Restarting alone does not resume the worker: a pod that starts while the halt row is uncleared
halts again. The DLQ may hold **more than one** copy of the poison message.

### Escalation

- If dead-lettered events involve transfers created in TigerBeetle, escalate to Platform Engineering within 15 minutes.
- Do NOT purge the DLQ. Dead-lettered records contain the raw payloads required for forensic recovery.

<a id="projection-drift"></a>

## Runbook — live projection drift

**Alert:** `ProjectionDrift` (`infrastructure/observability/alerts/financial-control.rules.yml`).
**When to use this.** `projection_drift_minor` is non-zero (positive or negative): the projected balances in PostgreSQL differ from the authoritative TigerBeetle ledger balances.

### Symptoms

- The page fires immediately (`severity: page`, `for: 0m`).
- `projection_drift_minor` is non-zero on the Financial Control Dashboard.
- Participants or employer administrators may see stale or inaccurate balances via `balance-api`.

### What is at risk

**Participants' money and customer trust.** TigerBeetle is the absolute system of record for all money and units (`ADR-0008`), but read-side APIs (`balance-api`, statements, portal queries) project from PostgreSQL (`projection.participant_units` and `projection.transfer`). Any drift means the projected view contradicts reality.

Drift is signed:
- **Negative drift**: The projection has recorded less money or fewer units than the ledger holds.
- **Positive drift**: The projection has recorded more money or more units than the ledger holds.

Both indicate either a dropped/duplicated CDC event, an uncommitted projection transaction, or an out-of-order projection write.

### First three diagnostic commands

1. **Identify the affected accounts and scale of drift:**
   ```sql
   -- Run against PostgreSQL projection database
   SELECT tenant_id, account_id, unit_ledger_id, units_scaled, updated_at
   FROM projection.participant_units
   ORDER BY updated_at DESC
   LIMIT 10;
   ```

2. **Check whether the projection consumer is merely lagging or truly stalled:**
   ```sql
   SELECT consumer_id, queue_name, last_ledger_timestamp, updated_at
   FROM projection.projection_consumer_checkpoint
   WHERE projection_name = 'projection-worker'
   ORDER BY last_ledger_timestamp;
   ```
   One row per consumer (`ADR-0032`). Compute the projection head with the query in `cdc-replay.md`
   §0 and compare it with the `ledger_head_timestamp` metric. If the projection checkpoint is actively advancing toward the head, the drift may be transient inflight lag that will self-resolve once caught up.

3. **Check for dead-lettered or failed events in the bridge or projection-worker logs:**
   ```bash
   # Kubernetes / Production:
   kubectl -n core logs deployment/projection-worker --tail=100 | grep -E "ERROR|WARN|MalformedLedgerEventException"

   # Local development / Compose:
   docker compose -f infrastructure/compose/docker-compose.yml logs --tail=100 amqp-redpanda-bridge
   ```

### Decision criteria

| Finding | Action |
|---|---|
| Checkpoint is advancing rapidly, lag shrinking | Inflight lag. Wait 30 seconds for catch-up. If drift clears to 0, resolve alert as transient. |
| Checkpoint is stalled, uncommitted errors in logs | Service or database connectivity issue. Restart `projection-worker` or resolve database connection pool exhaustion. |
| Checkpoint is current (`last_ledger_timestamp == ledger_head`), but drift persists | Data integrity break. Events were missed, dropped, or corrupted during transformation. Follow ``#cdc-replay`` to trigger a full projection rebuild from timestamp 0. |

**Never** manually update `projection.participant_units` rows to force numbers to match without replay. The projection is an idempotent downstream function of the ledger event stream (`ADR-0011`).

### Escalation

- If drift persists after a replay or exceeds 10,000 minor units across multiple accounts, page the core platform engineering lead immediately.
- Notify operations leadership that participant-facing balance reads should be treated as degraded until reconciliation passes.

<a id="idempotency-conflicts"></a>

## Runbook — idempotency conflict spike

**Alert:** `IdempotencyConflictSpike` (`infrastructure/observability/alerts/financial-control.rules.yml`).
**When to use this.** `idempotency_conflict_total` is rising continuously over a 5-minute evaluation window (`severity: warning`).

### Symptoms

- Warning alert fires.
- `idempotency_conflict_total` shows an upward slope on the Financial Control Dashboard.
- HTTP 409 Conflict responses are returned to calling clients (e.g. payroll ingest systems, contribution processors).

### What is at risk

**Client integration bugs and potential duplicate money movements.** An idempotency replay (`idempotency_replay_total`) is completely safe: a client sent the exact same payload twice with the same idempotency key, and the server returned the cached response.
An idempotency **conflict** (`idempotency_conflict_total`) means the client sent the same key with a *different* payload (e.g. different amount, different participant list, or different account). This indicates a buggy client or race condition in payroll generation.

### First three diagnostic commands

1. **Identify the calling tenant and API endpoints with conflicts:**
   ```bash
   # Kubernetes / Production:
   kubectl -n core logs deployment/contribution-api --tail=200 | grep -i "IdempotencyConflictException"

   # Loki / Log query:
   # {service_name="contribution-api"} |= "IdempotencyConflictException"
   ```

2. **Query the idempotency store for recent conflicts:**
   ```sql
   -- Inspect recent idempotency records
   SELECT idempotency_key, resource_type, created_at, request_hash
   FROM master.idempotency_key
   ORDER BY created_at DESC
   LIMIT 10;
   ```

3. **Determine if an automated integration is looping:**
   Check client IP address and user agent in the reverse proxy / ingress logs to see if a single client is retrying in a tight loop.

### Decision criteria

| Finding | Action |
|---|---|
| Single client integration bug (e.g. static/hardcoded idempotency key reused across batches) | Contact client technical team immediately to halt their batch job. No platform data corruption has occurred because conflicting writes are rejected. |
| Mismatched request hash due to unstable JSON serialization | Verify whether client payload contains timestamp or randomized fields that alter the hash. Advise client on canonical payload structure. |
| Malicious replay attack attempt | If key reuse appears adversarial, block calling API token / IP at the API gateway layer. |

### Escalation

- Because the platform rejects conflicting writes atomically, participant balances are safe.
- Contact the integration partner account team if the issue persists for more than 1 hour.

<a id="tigerbeetle-upgrades"></a>

## TigerBeetle Cluster Rolling Upgrade Runbook

**Target Component:** `infrastructure/k8s/base/tigerbeetle/` / `infrastructure/k8s/overlays/prod/`  
**Related Documents:** docs/DESIGN-DECISIONS.md, docs/DOMAIN.md, `ADR-0008` (TigerBeetle as the sole balance authority).

---

### 1. Safety Invariants & Architecture Rules

1. **TigerBeetle is the Single Balance Authority (`ADR-0008`)**:
   TigerBeetle maintains consensus via Viewstamped Replication (VSR). In production, the cluster runs **6 replicas** across 3 availability zones (`topologySpreadConstraints`).
2. **Consensus Quorum**:
   In a 6-replica cluster, quorum is **4 replicas**. The cluster survives up to **2 concurrent failures or restarts** without loss of availability or data.
3. **PDB Invariant**:
   `PodDisruptionBudget` sets `maxUnavailable: 1`. Kubernetes will strictly refuse voluntary disruptions of more than 1 replica at a time.
4. **Version Compatibility Window**:
   TigerBeetle's protocol compatibility between client and server versions is strictly enforced. **The client library version (`com.tigerbeetle:tigerbeetle-java`) and server binary version MUST match identically.**
5. **Direct I/O Storage Invariant**:
   TigerBeetle operates on dedicated `LocalPersistentVolume` NVMe storage (`tigerbeetle-nvme` StorageClass) with Direct I/O. **Network shares (NFS, Ceph, EBS) are strictly prohibited.**
6. **Format-Once Invariant**:
   The init container verifies whether `${TIGERBEETLE_DATA_DIR}/${CLUSTER_ID}_${ORDINAL}.tigerbeetle` exists. If it exists, formatting is skipped. If someone attempts to reformat, the init container rejects it, preserving volume integrity.

---

### 2. Rolling Upgrade Procedure

Follow these steps when upgrading TigerBeetle server version (e.g. `0.17.4` to `0.17.5`).

#### Step 1: Pre-Upgrade Verification
1. Verify cluster health:
   ```bash
   kubectl -n core exec tigerbeetle-0 -c tigerbeetle -- /tigerbeetle version
   ```
2. Verify all 6 pods are `Running` and `Ready`:
   ```bash
   kubectl -n core get pods -l app.kubernetes.io/name=tigerbeetle
   ```
3. Verify client compatibility: Ensure that all services consuming `libs/ledger-tigerbeetle` are compatible with the target TigerBeetle protocol version.

#### Step 2: Update Image Digest in Base Kustomization
1. Update `image` in `infrastructure/k8s/base/tigerbeetle/statefulset.yaml` (both init container and main container) with the new pinned SHA digest (`@sha256:`).
2. Update `infrastructure/k8s/base/tigerbeetle-cdc/deployment.yaml` with the corresponding image digest.
3. Run manifest validation:
   ```bash
   make verify-k8s
   ```

#### Step 3: Execute Rolling Update
Because `updateStrategy.type: RollingUpdate` and `podManagementPolicy: OrderedReady` are configured, Kubernetes updates replicas in reverse ordinal order: `tigerbeetle-5`, then `tigerbeetle-4`, down to `tigerbeetle-0`.

1. Apply the updated Kustomize overlay:
   ```bash
   kubectl apply -k infrastructure/k8s/overlays/prod
   ```
2. Monitor rolling update status:
   ```bash
   kubectl -n core rollout status statefulset/tigerbeetle --timeout=15m
   ```
3. Watch pod transitions:
   ```bash
   kubectl -n core get pods -l app.kubernetes.io/name=tigerbeetle -w
   ```

#### Step 4: Verify Consensus & Catchup After Each Replica
For each restarted replica:
1. Ensure startup and readiness probes pass:
   ```bash
   kubectl -n core describe pod tigerbeetle-<ordinal>
   ```
2. Confirm the replica syncs state with the cluster and participates in consensus.
3. Verify CDC streaming remains connected:
   ```bash
   kubectl -n core logs deployment/tigerbeetle-cdc -c tigerbeetle-cdc --tail=50
   ```

---

### 3. Rollback Constraints & Emergency Recovery

#### Rollback
- If an upgraded replica cannot form consensus or crashes on the new binary:
  1. Immediately pause the rollout:
     ```bash
     kubectl -n core rollout pause statefulset/tigerbeetle
     ```
  2. If the data file format version has not changed, rollback the image:
     ```bash
     kubectl -n core rollout undo statefulset/tigerbeetle
     ```
  3. Verify that the previous image version starts up and resumes consensus.

#### Data File Recovery / Total Disk Loss
If an NVMe local disk on a single node is permanently lost (hardware failure):
1. The remaining 5 replicas continue servicing transactions (quorum = 4).
2. The lost pod will restart with an empty volume.
3. The format-once init container will initialize a fresh replica file for that ordinal (`tigerbeetle format --cluster=0 --replica=<ordinal> ...`).
4. TigerBeetle's Viewstamped Replication protocol automatically syncs all historical blocks from the active peers to the recovered replica.
5. Once synced, the replica transitions to active consensus participation.

<a id="rabbitmq-node-failure"></a>

## Runbook — RabbitMQ Quorum Queue Durability on Node Failure

**Applies to:** `RabbitmqCluster` resource `rabbitmq` (`infrastructure/k8s/base/rabbitmq/rabbitmq-cluster.yaml`).  
**Purpose:** Operational guidance for handling RabbitMQ pod/node termination, cluster rejoin, and quorum queue durability verification.

---

### 1. Architecture & Invariants

- **3-Node Raft Quorum Cluster:** `replicas: 3` spread across 3 availability zones (`topologySpreadConstraints`).
- **Quorum Queues Default:** All mission-critical queues (`projection.transfers.v1`, `ledger.entries.v1`, `statement.jobs.v1`) are declared as Quorum Queues (`x-queue-type: quorum`).
- **Raft Consensus:** Raft replicates entries across a majority of nodes (2 out of 3). A single node failure does not interrupt reads, writes, or consensus.
- **`rabbitmq_consistent_hash_exchange` Enabled:** Routes messages deterministically by `account_id` into 16 consistent hash queues, ensuring per-account FIFO ordering is strictly preserved without Kafka partitions (ADR-0022 §2a).

---

### 2. Node Failure Simulation & Drill Procedure

1. **Inspect Cluster Status & Queue Leaders:**
   ```bash
   kubectl exec -n fondaria rabbitmq-server-0 -- rabbitmq-diagnostics cluster_status
   kubectl exec -n fondaria rabbitmq-server-0 -- rabbitmq-diagnostics check_running
   ```
   Check quorum queue leader and replica distributions:
   ```bash
   kubectl exec -n fondaria rabbitmq-server-0 -- rabbitmqctl list_queues name type leader members
   ```

2. **Publish Test Messages to a Quorum Queue:**
   Publish durable messages to a quorum queue with publisher confirms enabled. Confirm ack is received from the broker.

3. **Terminate One RabbitMQ Node Pod:**
   ```bash
   kubectl delete pod -n fondaria rabbitmq-server-1 --now
   ```

4. **Verify Service Continuity & Zero Message Loss:**
   - Client consumers and publishers continue without disruption.
   - If the terminated pod was a queue leader, Raft promotes one of the remaining two in-sync replicas within 1-2 seconds.
   - Consumer reads from the new leader and observes all committed messages intact.
   - Zero queued messages are lost.

5. **Verify Pod Rejoin & Re-synchronization:**
   - StatefulSet controller restarts `rabbitmq-server-1`.
   - Node rejoins the Raft cluster automatically.
   - Raft catches up missed entries via WAL replay.
   - Run `rabbitmq-diagnostics check_running` to verify full 3-node cluster health.

---

# Daily close and reconciliation

<a id="internal-reconciliation-drift"></a>

## Runbook — internal reconciliation drift

**Alert:** `EODInternalReconciliationDrift` (`infrastructure/observability/alerts/eod-close.rules.yml`).
**When to use this.** `eod_reconciliation_blocked` has stayed `1` for at least five
minutes: the latest `workflow.reconciliation_run` for a business date is not `PASSED` —
either it is still `RUNNING`, it `FAILED` outright, or it found one or more breaks
(Task 7.2, `ReconciliationGate`).

### Symptoms

- The page fires; `eod_reconciliation_blocked` is `1`.
- The daily close does not advance: `SnapshotService.snapshotFor` calls
  `ReconciliationGate.requirePassed` before it reads anything, so the snapshot (7.4)
  refuses to run for a business date whose latest reconciliation run is not `PASSED` —
  and `SignoffGate` (7.5a) then refuses without a completed snapshot, so sign-off and
  statements (7.5b) stall behind this one control too.
- The service log carries one of:
  ```
  internal reconciliation failed to complete: runId=... businessDate=...
  internal reconciliation complete: runId=... businessDate=... status=FAILED funds=... holdingAccounts=... cashAccounts=... breaks=N durationMillis=...
  ```

### What is at risk

**Participants' money, directly** — this is the control that proves the ledger's own
two derivations (`Σ participant units` and `units_in_issue`) and the CDC projection all
agree. A drift here means the platform's three sources of truth about who owns how many
units disagree with each other; nothing downstream (a balance shown to a participant, a
statement, a snapshot) can be trusted for this business date until it is resolved.
Nothing has been posted incorrectly by this alert alone — `ReconciliationGate` blocks the
chain precisely so that a wrong number never reaches a report — but every hour the gate
stays closed is an hour the close is not proving the books balance.

### First three diagnostic commands

1. **Is it a break, or a run that never finished?**
   ```sql
   SELECT run_id, status, break_count, started_at, completed_at, duration_millis
   FROM workflow.reconciliation_run
   WHERE business_date = '<business_date>'
   ORDER BY completed_at DESC NULLS LAST, started_at DESC
   LIMIT 5;
   ```
   `status = 'RUNNING'` with no `completed_at` well past the pass's usual duration means
   the process died mid-pass (a lost pod, an OOM) — nothing was found wrong with the
   books, the check itself did not finish. `status = 'FAILED'` with `break_count = 0`
   means the pass errored before it could compare anything (a bad `LedgerPort` call, a
   database timeout) — same category, the check did not run to completion. Only
   `status = 'FAILED'` with `break_count > 0` is an actual drift.

2. **What, exactly, disagrees?**
   ```sql
   SELECT break_no, scope, fund_id, unit_ledger_id, account_id,
          left_derivation, left_amount_scaled, right_derivation, right_amount_scaled,
          drift_scaled, detected_at
   FROM workflow.reconciliation_break
   WHERE run_id = '<run_id>'
   ORDER BY break_no;
   ```
   `scope = 'FUND_UNITS'` means two of `LEDGER_PARTICIPANT_UNITS`, `LEDGER_UNITS_IN_ISSUE`
   or `PROJECTION_UNITS` disagree for a fund; `scope = 'CASH_ACCOUNT'` means
   `LEDGER_CASH` and `PROJECTION_CASH` disagree for one cash account. `drift_scaled` is
   signed (left minus right) — a negative value means the ledger side holds *less* than
   the other derivation claims, which is exactly as real a finding as the reverse.

3. **Is the projection just behind, or genuinely wrong?**
   Read the projection head with the query in `cdc-replay.md` §0 (the lowest timestamp among
   consumers active in the last 5 minutes, from `projection.projection_consumer_checkpoint`).
   Compare against the ledger's own head. If the checkpoint is materially behind the
   ledger, the projection has not caught up yet and a break naming `PROJECTION_UNITS` or
   `PROJECTION_CASH` may resolve itself once it does — re-run reconciliation for the same
   date after the checkpoint advances before escalating. If the checkpoint is current and
   the break is still there, it is a real drift.

### Decision criteria

| Finding | Action |
|---|---|
| Run `RUNNING` past its usual duration, or `FAILED` with `break_count = 0` | Operational failure, not a drift. Check the pod/process; if it crashed, the next scheduled pass (`fondaria.reconciliation.poll-interval`) re-attempts automatically. No participant-facing decision to make. |
| Break with `PROJECTION_*` on one side and the checkpoint lagging | Wait for the projection to catch up (``#cdc-replay`` if it is stalled, not merely behind) and re-run before treating it as a real drift. |
| Break between the two **ledger-only** derivations (`LEDGER_PARTICIPANT_UNITS` vs. `LEDGER_UNITS_IN_ISSUE`), or a break that persists after the projection is confirmed current | Real drift. This is never fixed by adjusting a participant's units to match — there is no code path that does that, deliberately (docs/ENGINEERING-RULES.md §4). It is a data or ledger-client defect and needs engineering investigation, not an operational fix. |
| Same break reappears run after run | Escalate immediately — a break that keeps recurring after the projection has caught up means the discrepancy is structural, not transient. |

**Never** close the gate by hand. There is no operator action that marks a `FAILED` run
`PASSED` — `JdbcReconciliationRepository#complete` and `#fail` both guard their `UPDATE`
with `WHERE status = 'RUNNING'`, so a settled run (`PASSED` or `FAILED`) can never be
transitioned again by application code, and nothing exposes a way to do it by hand either.
Never adjust `participant_units` or a ledger balance to make the numbers agree. The only
way this alert clears is a subsequent pass finding zero breaks.

### Escalation

- A break between the two ledger-only derivations, or one that survives a confirmed-current
  projection: escalate immediately to the platform engineering on-call — this is a
  correctness defect in the ledger or the reconciliation logic itself, not a data-quality
  issue with a known fix.
- A run that will not complete for more than one poll interval running: escalate to
  platform engineering as an availability issue (the process, not the books).
- A break that reappears across three or more consecutive daily passes: escalate to
  whoever owns `libs/ledger-client` and `services/projection-worker`, since it points at a
  bug in one of the two components being compared rather than a one-off event.

### What must be reported, and to whom

- **Any confirmed real drift** (not a resolved projection-lag false alarm) must be
  reported to the platform owner and to compliance before the close is allowed to proceed
  for that business date, regardless of how small the drift is — `drift_scaled` is exact
  and signed, so "small" is not a reason to wave it through. The daily close stays blocked
  until the finding is understood, even if fixing the underlying cause takes longer.
- **A resolved false alarm** (projection catch-up, or a crashed pass that a re-run
  cleared) does not need to be reported beyond the incident log — the next `PASSED` run is
  the record that the close proceeded correctly for that date.
- Every report of a real drift must include: `run_id`, `business_date`, every row from
  `workflow.reconciliation_break` for that run, and whether the projection was confirmed
  current at the time (§ diagnostic 3) — the same data an auditor would need to
  reconstruct the finding years later (docs/ENGINEERING-RULES.md §4).

<a id="custodian-break"></a>

## Runbook — custodian break

**Alert:** `EODCustodianBreak` (`infrastructure/observability/alerts/eod-close.rules.yml`).
**When to use this.** `eod_custodian_breaks_total` increased: a `workflow.custodian_break`
row with `category IN ('UNIT_BREAK', 'CASH_BREAK')` is open — a real, unexplained
disagreement between what the external custodian reports and what this platform's own
ledger holds for a `ProviderAccount` (Task 7.3). **This does not necessarily mean a *new*
break** — `CustodianReconciliationMetrics#record` increments this counter from every
break the pass "opened or re-confirmed" (`CustodianReconciliationResult`'s own contract),
so an unresolved break re-pages on every pass that still finds it, not only its first
one. Diagnostic 1 below is what tells a brand-new break from a still-open one you already
know about. **When NOT to use this:** a `*_TIMING_DIFFERENCE` category is expected, ages
on its own longer threshold, and does not page on its own — see
``#late-custodian-file`` if the symptom is a *missing* statement instead of
a disagreeing one.

### Symptoms

- The page fires; `eod_custodian_breaks_total` incremented (on the break's first pass, or
  on any later pass that still finds it open).
- `CustodianReconciliationGate` blocks the business date's close — the snapshot (7.4),
  sign-off (7.5a) and statements (7.5b) all refuse to run until the run passes.
- The service log carries:
  ```
  custodian reconciliation complete: runId=... businessDate=... status=FAILED providerAccounts=... missingStatements=0 realBreaks=N timingDifferences=... escalated=... durationMillis=...
  ```

### What is at risk

**This is the most important control in the platform**, because the counterparty's
records are the truth and this platform does not control them (`phase-07-eod.md` Task
7.3). A real break means the custodian's statement — units or cash — does not agree with
`LedgerPort`'s resolution of that `ProviderAccount`'s scope, after accounting for every
pending trade the statement itself reports. Left unresolved, either this platform's
ledger is showing participants a position the custodian does not confirm holding, or the
custodian's own books are wrong; either way, the close cannot certify that participants'
money is where the ledger says it is.

### First three diagnostic commands

1. **Which account, which break, how big, how old?**
   ```sql
   SELECT break_id, provider_account_id, category, status, drift_scaled,
          first_detected_business_date, last_confirmed_business_date,
          assigned_to, opened_at, escalated_at
   FROM workflow.custodian_break
   WHERE status <> 'RESOLVED'
     AND category IN ('UNIT_BREAK', 'CASH_BREAK')
   ORDER BY first_detected_business_date;
   ```
   `drift_scaled` is signed and, for a `UNIT_BREAK`, is Units-scaled (1e-8); for a
   `CASH_BREAK`, MXN cents. Age is `<today> - first_detected_business_date`, computed at
   read time — the column is never stale between runs.

2. **What did the custodian actually report, and what did the ledger say?**
   ```sql
   SELECT s.business_date, s.reported_units_scaled, s.reported_cash_cents,
          s.pending_trade_units_scaled, s.pending_trade_cash_cents, s.source, s.received_at
   FROM workflow.custodian_statement s
   WHERE s.provider_account_id = '<provider_account_id>'
   ORDER BY s.business_date DESC
   LIMIT 5;
   ```
   Confirm `pending_trade_*` is not simply unaccounted for — a break is only real once
   the statement's own pending-trade figures have been subtracted and a disagreement
   remains (Task 7.3: timing differences are driven by a figure the statement carries,
   never inferred from ledger transfer timestamps).

3. **Which `ProviderAccount`, and what scope does it reconcile?**
   ```sql
   SELECT provider_account_id, fund_id, scope_type, organization_id, provider_ref,
          effective_from, version
   FROM reference.provider_account
   WHERE provider_account_id = '<provider_account_id>';
   ```
   `scope_type = 'ALL_PARTICIPANTS'` reconciles against the fund's `units_in_issue`
   directly; `scope_type = 'ORGANIZATION'` reconciles against that employer's summed
   holdings only (`ADR-0019`). Knowing the scope tells you how wide a slice of
   participants the break could touch — one employer, or the whole fund.

### Decision criteria

- **Escalation thresholds are configuration, not a fixed number to memorize** —
  `fondaria.custodian-reconciliation.real-break-escalation-days` (default **3 days**) for
  `UNIT_BREAK`/`CASH_BREAK`, `fondaria.custodian-reconciliation.timing-difference-escalation-days`
  (default **5 days**) for a timing difference. Read the live values from `services/eod-job`'s configuration
  before deciding whether a break's age already warrants escalation — `status = 'ESCALATED'`
  on the row means the ageing sweep already made that call for you.
- **Never adjust participant units or cash to match the custodian.** There is no code
  path that does this — `eod-job` is deliberately never wired to a `TransferIdDeriver`
  (`ADR-0018`). If the custodian is right and the ledger is wrong, that is a ledger
  correction through the normal transfer/correction path (a new, signed transfer), never
  a direct edit here. If the ledger is right and the custodian is wrong, the custodian
  must reissue a corrected statement — this platform does not overwrite what it reported.
- A break whose `drift_scaled` exactly matches a known, explainable event (a contribution
  or withdrawal posted the same day the custodian's cut-off missed) is a strong signal the
  custodian's file is simply stale, not that the ledger is wrong — but do not resolve the
  break on that basis alone; get the custodian to confirm before marking it `RESOLVED`.
- A break under investigation is `ASSIGNED` or `INVESTIGATING` — set explicitly by an
  operator (`assigned_to`, `status_updated_at`); there is no automatic transition into
  either state, so an unassigned open break has had no one look at it yet.

### Escalation

- **Immediately**, regardless of age: any break whose `drift_scaled` implies participants
  collectively hold more units than the custodian confirms exist for that fund — this is
  a solvency question, not an ageing one.
- At the configured real-break threshold (default 3 days) if still `OPEN` or `ASSIGNED`:
  escalate to the custodian relationship owner to chase a response, and to platform
  engineering if the discrepancy looks like a platform-side defect rather than a
  provider-side one.
- Any break on an `ORGANIZATION`-scoped `ProviderAccount` (segregated custody): also
  notify that employer's account owner, since the break is specific to their participants
  rather than the fund as a whole.

### What must be reported, and to whom

- Every `UNIT_BREAK`/`CASH_BREAK` still open past its escalation threshold is reported to
  the platform owner and compliance, with: `break_id`, `provider_account_id`, `category`,
  `drift_scaled`, age, and the custodian statement(s) it was compared against (§
  diagnostic 2) — this is regulatory-facing evidence (`ADR-0019`, `O-35`), not an internal
  note.
- A break resolved by a corrected custodian statement is reported with the corrected
  statement's `checksum_sha256` and `received_at`, so the resolution is reproducible from
  stored data (docs/ENGINEERING-RULES.md §4) rather than "the custodian said it was fixed."
- A break resolved by a ledger correction is reported with the correcting transfer's id,
  never by editing `workflow.custodian_break` itself — the row stays as the historical
  record that a break existed and was investigated.

<a id="late-custodian-file"></a>

## Runbook — late custodian file

**Alert:** `EODCustodianStatementLate` (`infrastructure/observability/alerts/eod-close.rules.yml`).
**When to use this.** `eod_custodian_statements_late_total` increased: an active
`ProviderAccount` had no `workflow.custodian_statement` row for the business date when
today's custodian-reconciliation pass ran (Task 7.3). **When NOT to use this:** the file
arrived but disagrees with the ledger — that is ``#custodian-break``.

### Symptoms

- The page fires; `eod_custodian_statements_late_total` incremented.
- The service log carries, once per missing account:
  ```
  custodian statement missing: providerAccountId=... businessDate=...
  ```
  and the pass settles:
  ```
  custodian reconciliation complete: runId=... businessDate=... status=FAILED providerAccounts=... missingStatements=N realBreaks=... timingDifferences=... escalated=... durationMillis=...
  ```
- `CustodianReconciliationGate` blocks the business date exactly as a real break does —
  Task 7.3's posture is fail closed: **the daily pass itself is the deadline**, so there
  is no separate lateness grace period before this blocks the close.

### What is at risk

**Reporting, not (yet) the ledger.** A missing statement means this platform cannot prove
the custodian agrees with its own figures for that `ProviderAccount` — it does not mean
the ledger is wrong, only that the day's most important control (Task 7.3: "the most
important control in the platform, because the counterparty's records are the truth")
could not run for that account. Every downstream step that depends on custodian
reconciliation having passed (the snapshot, sign-off, statements) is blocked for the
*whole* business date, not just the affected `ProviderAccount` — one slow provider file
stalls every fund's close.

### First three diagnostic commands

1. **Which accounts are missing, and since when?**
   ```sql
   WITH active_accounts AS (
     -- The same DISTINCT ON JdbcProviderAccountDirectory#activeAsOf uses: reference.provider_account
     -- is effective-dated, so a superseded version (e.g. an employer moved omnibus -> segregated)
     -- still satisfies effective_from <= businessDate and must be excluded, or it shows up here
     -- forever even though no statement is ever expected against it again.
     SELECT DISTINCT ON (fund_id, scope_type, organization_id)
            provider_account_id, fund_id, scope_type, organization_id, provider_ref
       FROM reference.provider_account
      WHERE effective_from <= '<business_date>'
      ORDER BY fund_id, scope_type, organization_id, effective_from DESC, version DESC
   )
   SELECT aa.provider_account_id, aa.fund_id, aa.scope_type, aa.organization_id, aa.provider_ref
   FROM active_accounts aa
   WHERE NOT EXISTS (
     SELECT 1 FROM workflow.custodian_statement cs
     WHERE cs.provider_account_id = aa.provider_account_id
       AND cs.business_date = '<business_date>'
   );
   ```
   This is the same "active accounts, no statement" comparison the pass itself makes.
   `provider_ref` is the counterparty's own account identifier — what you give the
   custodian's operations desk when you call them.

2. **Has this account been late before?**
   ```sql
   SELECT ccr.business_date, ccr.missing_statement_count
   FROM workflow.custodian_reconciliation_run ccr
   WHERE ccr.business_date BETWEEN '<business_date>' - INTERVAL '10 days' AND '<business_date>'
   ORDER BY ccr.business_date;
   ```
   A `missing_statement_count` that has been nonzero for several consecutive days points
   at a broken feed (a provider-side outage, a changed delivery path) rather than a
   one-off delay; a single day is more likely a genuine late file.

3. **When the file finally arrives, what does it show?**
   Once you have the file from the custodian, confirm its checksum before doing anything
   with it — `CustodianStatementIngestionService` is idempotent on
   `(providerAccountId, businessDate)` and refuses a resubmission whose checksum differs
   from one already on file rather than silently replacing it. There is no operator
   endpoint yet for this ingestion path; recording it today is a direct insert matching
   what that service would do:
   ```sql
   INSERT INTO workflow.custodian_statement (
       statement_id, provider_account_id, business_date,
       reported_units_scaled, reported_cash_cents,
       source, checksum_sha256, received_at
   ) VALUES (
       gen_random_uuid(), '<provider_account_id>', '<business_date>',
       '<reported_units_scaled>', '<reported_cash_cents>',
       '<source, e.g. sftp-manual>', '<checksum_sha256 of the file received>', now()
   );
   ```
   `pending_trade_units_scaled` / `pending_trade_cash_cents` are omitted deliberately — the
   column defaults (`DEFAULT 0`, `V27`) already give "the statement reports no pending
   trades", and a literal placeholder substituted with an empty value is never SQL `NULL`,
   so wrapping it in `COALESCE(..., 0)` would not help and would instead fail with
   `invalid input syntax for type bigint` on an empty string. If the custodian's file
   *does* report pending trades, add both columns explicitly with the signed figures it
   carries (Task 7.3: never inferred from ledger transfer timestamps).

   `custodian_statement_one_per_account_and_date` (a `UNIQUE` constraint) refuses a second
   row for the same account and date, so a repeat of this insert for a file already
   recorded fails loudly rather than creating a duplicate.

### Decision criteria

- **The file is genuinely late but the custodian confirms it is coming**: no action other
  than waiting and re-running the custodian-reconciliation pass once it lands
  (`fondaria.custodian-reconciliation.poll-interval`, default hourly) — the close resumes on
  its own the moment the statement is recorded and reconciles clean.
- **The file will not arrive today at all**: the close for this business date stays
  blocked. There is no override — do not sign off a date whose custodian side was never
  proved, even under time pressure to publish statements. This is deliberate: the whole
  reason Task 7.3 fails closed is that an unreconciled position is exactly as unproved as
  a wrongly reconciled one.
- **The feed has been broken for multiple consecutive days**: stop waiting on the next
  file and treat it as a vendor-integration incident — engage the custodian's operations
  desk directly rather than continuing to page on the daily pass.
- **A statement for a wrong business date, or with a checksum that does not match a
  resubmission**: `custodian_statement_one_per_account_and_date` and the ingestion
  service's checksum check both refuse it. Do not force the insert past a
  `StatementConflictException` / the unique constraint — get a corrected file instead.

### Escalation

- Any account still missing its statement past two consecutive daily passes: escalate to
  the custodian relationship owner to chase the provider directly, not just to re-poll.
- A feed broken for **three or more** consecutive business dates: escalate to the platform
  owner as a vendor-integration incident, since the close has now been blocked for
  multiple days running.
- A statement that arrives but the checksum does not match what the custodian's own
  confirmation says they sent: escalate to platform engineering immediately — this is
  either transport corruption or a possible integrity problem with the file itself.

### What must be reported, and to whom

- Every business date the close remained blocked past its normal window because of a late
  statement is reported to the platform owner, with: the list of missing
  `provider_account_id`s (§ diagnostic 1), how many consecutive days each has been
  missing (§ diagnostic 2), and when (or whether) the statement was eventually received.
- If statements (7.5b) for a month-end were delayed because of this, also notify whoever
  owns the statement schedule and, for an `ORGANIZATION`-scoped account, that employer's
  account owner — a late file on a segregated account can delay reporting for one
  employer specifically.
- A recurring vendor-integration incident (three or more late days) is reported to
  compliance as well as the platform owner, since it bears on whether the custodian
  relationship itself — not just this month's close — needs review.

<a id="reconciliation-tie-break"></a>

## Runbook — breaking a reconciliation tie

**When to use this.** A contribution batch is stuck in `PENDING_RECONCILIATION`, its
cash *has* arrived, and the reconciliation gate has declined to match it because the
answer is ambiguous. Task 3.4, `ReconciliationGate`.

**When NOT to use this.** If the cash simply has not arrived, do nothing — the batch
resolves itself on the next sweep once the wire is posted (`ADR-0010`). This procedure
assigns an employer's money to a batch by hand. It is the only manual money-adjacent
step in the contribution path, and it must be a deliberate decision by someone who
knows which wire is which.

### 1. Confirm it is actually a tie

```
GET /v1/organizations/{organizationId}/reconciliation/ageing
```

The batch appears under `unmatchedFiles` with its `declaredTotalCents`. Look at
`unmatchedCash`: the gate declines when

- **two or more** unclaimed receipts each equal `declaredTotalCents` exactly — it will
  not choose between them, because choosing is deciding whose money this is; or
- no single receipt matches and the receipts do not sum to the total either, but some
  subset does. There is no subset search, deliberately: several subsets can hit the
  same total and nothing in the data says which is right.

The service log carries the first case explicitly:

```
Reconciliation declined: N unclaimed receipts each equal this batch's declared total,
so which one funds it is ambiguous, organizationId=...
```

If neither shape applies, stop — the amounts genuinely do not reconcile, and assigning
cash will not fix that. `amountCents` on the report is read from the ledger, so a
receipt showing `null` there is a discrepancy to investigate, never one to assign.

### 2. Decide which receipts fund the batch

From the bank statement and the employer's own reference (`bank_reference` on
`workflow.cash_receipt`, and the batch's `payrollPeriod`). This is the judgement the
platform declines to make automatically.

The receipts you choose must sum **exactly** to `declaredTotalCents`. A partial
assignment leaves the batch blocked and is **not** topped up from whatever else is
outstanding — mixing a human's partial decision with automatic matching would consume
wires nobody chose.

### 3. Assign them

One row per receipt, in one transaction:

```sql
BEGIN;
INSERT INTO workflow.contribution_batch_cash_claim
    (organization_id, transfer_id, batch_id, claimed_at)
VALUES ('<organization_id>', '<transfer_id>', '<batch_id>', now());
-- repeat for each receipt funding this batch
COMMIT;
```

Do not touch `workflow.contribution_batch` — no status update, no `UPDATE`. The next
validation sweep sees the batch funded (rule 0 of the gate) and moves it to `VALIDATED`
on its own, re-reading every amount from the ledger as it does. Setting the status by
hand would skip that check.

The table's primary key refuses a receipt another batch already holds, so a mistaken
assignment fails rather than taking someone else's money.

### 4. Confirm

```
GET /v1/organizations/{organizationId}/contribution-batches/{batchId}
```

within one poll interval (`BATCH_VALIDATION_POLL_INTERVAL`, 5s by default). Expect
`VALIDATED`. The service logs:

```
Contribution batch validated against cash already assigned to it, batchId=..., receiptsHeld=N
```

If it stays `PENDING_RECONCILIATION`, the assigned receipts do not sum to the declared
total, or the ledger does not confirm one of them. Both cases are logged; neither is
fixed by assigning more cash.

### Undoing an assignment

Delete the claim rows before the batch reaches `VALIDATED` and it returns to the
automatic path. After it is `VALIDATED`, do not delete them: the batch is funded, later
tasks stage against it, and a correction is a new batch referencing the original
(Task 3.6), never a deletion here.

### Why this is manual at all

`EMPLOYER_COLLECTIONS[org]` is one running balance across every batch an employer has
ever sent, so the ledger cannot say which cash belongs to which batch, and summing
`workflow.cash_receipt` is forbidden (`ADR-0008` invariant 1). The allocation has to be
recorded explicitly, and where the allocation is ambiguous the platform stops rather
than guessing. A future operator endpoint will replace the SQL above with an
authenticated, audited call; the underlying model will not change.

<a id="snapshot-tie-out-failure"></a>

## Runbook — snapshot tie-out failure

**Alert:** `EODSnapshotTieOutFailure` (`infrastructure/observability/alerts/eod-close.rules.yml`).
**When to use this.** `eod_snapshot_untied_total` increased: the dense daily snapshot
(Task 7.4) built a file whose `Σ units` for one or more funds did not equal that fund's
`units_in_issue`, read live from `LedgerPort`. This is the single check that proves the
snapshot file complete (`phase-07-eod.md` Task 7.4) — a fund failing it means the file is
wrong about participants' money, not merely inconsistent with an earlier day's snapshot.

### Symptoms

- The page fires; `eod_snapshot_untied_total` incremented.
- No file is stored for the run, and no `workflow.snapshot_fund_total` rows either — both
  are written only by a run that completes (`JdbcSnapshotRepository#complete`); a tied-out
  run never reaches that call. `SnapshotService` records the run `FAILED` with a
  `failure_reason` instead (`settleFailed`, `runs.fail`).
- The service log carries:
  ```
  snapshot failed: runId=... businessDate=... reason=...
  ```
- Downstream: `SnapshotGate` blocks sign-off (7.5a) and statement generation (7.5b) for
  this business date — there is nothing to render statements from until this passes.

### What is at risk

**The reporting side, not the ledger itself.** The tie compares the snapshot file's own
unit total against `units_in_issue`, which the snapshot reads but does not write — a
failed tie means the *file* is incomplete or wrong (a scan that stopped early, a position
dropped, a legitimate transfer landing between the two reads — diagnostic 2), not that
the ledger disagrees with itself the way Task 7.2's control would catch. That said,
statements for 250,000 participants a
month render from exactly this file (`ADR-0021`) — an untied snapshot that were ever
stored would mean participants receiving a statement for units they do not hold, or
missing units they do. The gate exists precisely so that never happens; a page here means
the gate did its job, not that participants have already seen a wrong number.

### First three diagnostic commands

1. **Which fund, and by how much did it miss?**
   ```sql
   SELECT run_id, business_date, status, failure_reason, started_at, completed_at
   FROM workflow.snapshot_run
   WHERE business_date = '<business_date>'
   ORDER BY completed_at DESC NULLS LAST, started_at DESC;
   ```
   `failure_reason` (`SnapshotNotTiedException#describe`) already names every fund that
   failed, in the form `"N fund(s) did not tie to units in issue: fund <id> holds <units
   in the file> against <units in issue> (drift <signed, at 1e-8>);…"`. There is
   deliberately no `workflow.snapshot_fund_total` row to join to for a tied-out run — that
   table is written only when a run completes (`JdbcSnapshotRepository#complete`), and a
   run that fails the tie never reaches that call. `failure_reason` is the only record of
   the drift, which is exactly why it carries the numbers inline rather than a fund id
   alone.

2. **Was this a legitimate late-arriving transfer, or a genuine gap?**
   ```sql
   SELECT a.fund_id, a.transfer_id, a.units_scaled, a.allotted_at
   FROM workflow.allotment a
   WHERE a.fund_id = '<fund_id from failure_reason>'
     AND a.allotted_at BETWEEN '<business_date>' AND (
       SELECT started_at FROM workflow.snapshot_run WHERE run_id = '<run_id>'
     )
   ORDER BY a.allotted_at DESC;
   ```
   `SnapshotService` reads `units_in_issue` **live** from `LedgerPort` at pass time but
   reads `participant_units` **as of `business_date`** — two different instants by
   design. A transfer that posts for this fund in the gap between them shows up in
   `units_in_issue` but not yet in the as-of file, and its `units_scaled` will match the
   drift in `failure_reason` exactly. That is not a defect — the pass simply ran while the
   book was still moving — and a re-run after the gap closes is expected to tie.

3. **Does the ledger agree with itself the same day?**
   ```sql
   SELECT break_no, scope, fund_id, drift_scaled
   FROM workflow.reconciliation_break rb
   JOIN workflow.reconciliation_run rr ON rr.run_id = rb.run_id
   WHERE rr.business_date = '<business_date>' AND rb.scope = 'FUND_UNITS';
   ```
   If internal reconciliation (7.2) also found a `FUND_UNITS` break for the same fund and
   date, the two symptoms share a root cause — go to
   ``#internal-reconciliation-drift`` instead of treating this as a
   snapshot-only defect. If 7.2 passed clean and diagnostic 2 found no explaining
   transfer, the problem is specific to the snapshot's own scan (the source
   `participant_units` read, not `units_in_issue` itself).

### Decision criteria

- **Diagnostic 2 finds a transfer whose units exactly explain the drift** → not a defect;
  wait for the next sweep (or re-run manually) once the transfer has settled. No
  escalation needed for a one-off case of this shape.
- **7.2 also failed for the same fund and date** → this is an internal-reconciliation
  drift wearing a second alarm; resolve that first (its runbook), then re-run the
  snapshot — it should tie once the underlying disagreement is fixed.
- **7.2 passed clean and no explaining transfer exists** → the scan itself skipped or
  double-counted a position. This needs engineering investigation into
  `SnapshotService`'s source read (`JdbcSnapshotSourcesIntegrationTest` is the as-of read
  this depends on) before re-running — re-running without understanding why will very
  likely reproduce the same gap.
- **Never** hand-edit `workflow.snapshot_fund_total` or fabricate a `positions_object_key`
  to unblock sign-off — there is no row to edit for a tied-out run in the first place
  (diagnostic 1), and fabricating one would let a snapshot that never tied masquerade as
  one that did, exactly what the append-only trigger on `workflow.snapshot_fund_total`
  and `SnapshotService` never writing a `positions_object_key` for a `FAILED` run exist to
  prevent.

### Escalation

- Any untie that is **not** explained by a late-arriving transfer (diagnostic 2) or a
  matching 7.2 break (diagnostic 3): escalate immediately to platform engineering — this
  is a defect in the snapshot's own source read, and every day it stays broken is a day
  statements cannot be generated.
- A pattern of untied runs that always trace to a late-arriving transfer: escalate to
  whoever owns `eod-job`'s scheduling — the pass may be running too soon after cutoff for
  the day's last contributions to settle, which is a timing tune, not a correctness bug.
- If the business date is close to a **month boundary** (the date `SignoffRunner`/
  `close.signed-off.v1` marks `lastBusinessDayOfMonth`): escalate with elevated urgency,
  since Task 7.5b's monthly statement fan-out is waiting on exactly this snapshot.

### What must be reported, and to whom

- Every tie failure is reported to the platform owner, with: `run_id`, `business_date`,
  the run's `failure_reason` naming every affected fund and its signed drift (§
  diagnostic 1), whether a late-arriving transfer explains it (§ diagnostic 2), and
  whether a matching Task 7.2 break was found (§ diagnostic 3) — this is what lets someone
  reconstruct, from stored data alone, whether the snapshot or the ledger was the source
  of the disagreement (docs/ENGINEERING-RULES.md §4).
- If the business date is a month-end, also notify whoever owns the statement schedule —
  a delayed monthly close has a participant-facing deadline the daily close does not.
- A tie failure that turns out to be caused by a bug fixed in code is reported with the
  fix (commit / PR), not just "resolved" — the next reader of this incident needs to know
  whether the same class of gap could recur.

<a id="eod-hash-chain"></a>

## Runbook — EOD hash chain invalid

**Alert:** `EODHashChainInvalid` (`infrastructure/observability/alerts/financial-control.rules.yml`).
**When to use this.** `eod_hash_chain_valid == 0`: the daily end-of-day reconciliation SHA-256 cryptographic hash chain verification has failed, indicating audit trail breakage, database corruption, or unauthorized data modification.

### Symptoms

- Page fires immediately (`severity: page`, `for: 0m`).
- `eod_hash_chain_valid` drops to `0`.
- The daily close job (`eod-job`) halts or marks the business date sign-off blocked (`eod_signoff_blocked == 1`).

### What is at risk

**Regulatory compliance and legal auditability.** The EOD hash chain provides tamper-evident proof that daily reconciliation outputs, participant unit totals, and fund strike valuations were verified sequentially without retrospective modification. An invalid chain means auditor sign-off cannot be given and statements cannot be rendered.

### First three diagnostic commands

1. **Locate the point of break in the hash chain:**
   ```sql
   -- Query the completed snapshot runs ordered by business date
   SELECT run_id, business_date, status, manifest_sha256, previous_manifest_sha256, completed_at
   FROM workflow.snapshot_run
   WHERE status = 'COMPLETED'
   ORDER BY business_date DESC
   LIMIT 10;
   ```

2. **Verify the computed hash against the recorded hash:**
   Check `eod-job` log output for the specific mismatch details:
   ```bash
   # Kubernetes / Production:
   kubectl -n core logs job/eod-close --tail=200 | grep -i -E "hash|chain|mismatch|verification failed"

   # Loki / Log query:
   # {service_name="eod-job"} |= "hash" |= "mismatch"
   ```

3. **Check database audit logs for direct modifications:**
   Inspect PostgreSQL WAL and connection logs to identify whether `UPDATE` or `DELETE` statements were executed against `workflow.snapshot_run` or related audit tables.

### Decision criteria

| Finding | Action |
|---|---|
| Out-of-order execution or duplicate close attempt | Verify whether an operator manually triggered a re-run for an already settled business date. Inspect `workflow.snapshot_run`. |
| Manifest hash computed from object differs from `manifest_sha256` | Data within `workflow.snapshot_run` or object storage was altered after recording. Treat as a security and compliance incident. Freeze database credentials and escalate. |
| `previous_manifest_sha256` does not match prior business date's `manifest_sha256` | Chain continuity break. Determine whether a business date was skipped or a snapshot failed to commit sequentially. |

### Escalation

- Immediately notify the Security Officer, Chief Technology Officer, and Head of Compliance.
- Halt statement generation jobs until chain integrity is formally validated.

<a id="missed-signoff"></a>

## Runbook — missed sign-off

**Alert:** `EODMissedSignoff` (`infrastructure/observability/alerts/eod-close.rules.yml`).
This runbook covers two different incidents that share the same table
(`workflow.close_signoff`, Task 7.5a) and the same root question — "can this business
date's numbers be trusted enough to report from" — so they are worked the same way.

- **§1 The close has not signed off** (what the alert pages on): `eod_signoff_blocked` has
  stayed `1` continuously for at least two hours.
- **§2 A date was signed off, and it should not have been**: discovered after the fact,
  typically because a later reconciliation re-run finds something the sign-off's own runs
  did not. There is no alert for this — `workflow.close_signoff` intentionally has no
  status/revoked state (see the migration comment on `V29__close_signoff.sql`) — so it
  only ever surfaces as "someone noticed a signed-off date looks wrong."

### §1 — The close has not signed off

#### Symptoms

- The page fires: `eod_signoff_blocked` has been `1` continuously for at least two hours
  (`for: 2h` in the rule) — "not signed off yet" is normal for the first two hours after
  the business-date cutoff (`CALENDAR_CUTOFF_TIME`, default `18:00` America/Mexico_City),
  while the close is still running, and is not what this page means.
- The service log carries:
  ```
  the daily close is not yet ready to sign off: <upstream gate's message>
  ```
  or, if the pass itself is erroring rather than being blocked:
  ```
  the sign-off pass did not complete
  ```

#### What is at risk

**Reporting, not the ledger.** `SignoffGate` is the last of four gates
(`ReconciliationGate` → `CustodianReconciliationGate` → `SnapshotGate` → this one); by the
time sign-off is blocked, one of the first three has already found something, and *that*
gate's own alert and runbook are where the underlying risk is described. What this alert
adds is time pressure: statement generation (7.5b) refuses to run at all without a
`workflow.close_signoff` row, so every hour this stays blocked is an hour further behind
on 250,000 participants' statements for the month this date belongs to.

#### First three diagnostic commands

1. **Is there a row at all, for this date?**
   ```sql
   SELECT business_date, signed_off_at, reconciliation_run_id, custodian_run_id,
          snapshot_run_id, snapshot_position_count, announced_at
   FROM workflow.close_signoff
   WHERE business_date = '<business_date>';
   ```
   No row: go to step 2. A row exists with `announced_at IS NULL`: this is not a missed
   sign-off at all — the date *is* signed off, only the `close.signed-off.v1` event has
   not been confirmed yet (a broker outage). `eod_signoff_announce_pending` is the gauge
   for that condition specifically; the next healthy sweep re-publishes automatically,
   nothing to do here.

2. **Which upstream gate is actually closed?**
   ```sql
   SELECT 'reconciliation' AS gate, status FROM workflow.reconciliation_run
   WHERE business_date = '<business_date>'
   ORDER BY completed_at DESC NULLS LAST, started_at DESC LIMIT 1;

   SELECT 'custodian' AS gate, status FROM workflow.custodian_reconciliation_run
   WHERE business_date = '<business_date>'
   ORDER BY completed_at DESC NULLS LAST, started_at DESC LIMIT 1;

   SELECT 'snapshot' AS gate, status FROM workflow.snapshot_run
   WHERE business_date = '<business_date>'
   ORDER BY completed_at DESC NULLS LAST, started_at DESC LIMIT 1;
   ```
   Whichever is not `PASSED` (or `COMPLETED`, for the snapshot) is the actual problem —
   go to that gate's own runbook (`internal-reconciliation-drift.md`,
   `custodian-break.md` / `late-custodian-file.md`, or `snapshot-tie-out-failure.md`).
   All three `PASSED`/`COMPLETED` but still no `close_signoff` row means the sign-off
   pass itself is erroring — check the `eod-job` process/pod health next.

3. **Is the process even running the pass?**
   `GET /actuator/health` on the `eod-job` instance(s). A pod that is down or failing
   readiness means the scheduled pass (`fondaria.signoff.poll-interval`) is not firing at
   all — this is an availability incident, not a data one, and no upstream gate needs
   investigating until the process is back up.

#### Decision criteria

- One of the three upstream gates is closed → work that gate's runbook; sign-off clears
  itself the next sweep after it opens. Do not attempt to sign off the date any other way
  — there is no operator action that writes a `close_signoff` row directly; the schema
  gives that write to `SignoffService` alone, gated on all three upstream checks.
- All three gates passed and no row exists → the sign-off pass is failing to run or to
  complete; escalate to platform engineering rather than continuing to wait on the poll
  interval.
- A row exists with `announced_at IS NULL` → not this incident; wait for the next sweep
  (`eod_signoff_announce_pending` is the metric to watch, not this alert).

#### Escalation

- More than 4 hours after cutoff with no sign-off and the blocking gate identified:
  escalate per that gate's own runbook — this alert's job is to notice, not to diagnose
  further once the cause is known.
- All three gates passed, no row, and the process is healthy: escalate to platform
  engineering immediately as a `SignoffService` defect — this should not be possible by
  design (`SignoffGate`'s javadoc: "not signed off" is the only closed state and it has
  exactly one cause, an unopened upstream gate).

### §2 — A signed-off date turns out to be wrong

#### Symptoms

- A later reconciliation re-run, an operator investigation, or an external report finds a
  discrepancy for a business date that already has a `workflow.close_signoff` row —
  statements may already have gone out for it.

#### What is at risk

**Everything downstream that trusted the sign-off.** Statements render only from a
signed-off date (`ADR-0021`); if the sign-off should not have happened, every statement
generated from it is suspect. This is deliberately **not** a state
`workflow.close_signoff` models — the table has no status and no revocation path (see the
comment on `V29__close_signoff.sql`) — so re-opening a date is a manual, deliberate act.

#### Decision criteria

- **Confirm the discrepancy is real before touching anything.** Re-run internal (7.2) and
  custodian (7.3) reconciliation for the business date and inspect the results the same
  way `internal-reconciliation-drift.md` / `custodian-break.md` describe. A transient
  fluke (e.g. the projection was briefly behind) is not a reason to re-open a sign-off.
- **Re-opening is a `DELETE`, not an `UPDATE`.** The table intentionally takes no status
  column for this — per the migration's own note, this "should not need a migration":
  ```sql
  DELETE FROM workflow.close_signoff WHERE business_date = '<business_date>';
  ```
  **If `<business_date>` is today's business date, the `DELETE` alone does not hold** —
  `SignoffService#signOff` targets `calendar.businessDateOf(clock.instant())` on *every*
  sweep (default hourly), and if the three upstream runs still read `PASSED`/`COMPLETED`
  for that date, the very next sweep re-inserts the row with a fresh `signed_off_at` and
  re-publishes `close.signed-off.v1`, re-triggering the 7.5b statement fan-out — undoing
  the `DELETE` within one poll interval. **Close at least one upstream gate before
  deleting**: re-running the reconciliation or custodian check that actually found the
  discrepancy should itself record a new `FAILED` run for the date (7.2/7.3's own
  behaviour), which is what makes the `DELETE` stick. If the issue is specific to the
  snapshot rather than reconciliation, `SnapshotService#verifyAgainst` records `FAILED`
  the same way on a re-run that finds the stored file no longer matches
  (``#snapshot-tie-out-failure``) — the general rule is: whichever gate's
  evidence turned out wrong, make sure a `FAILED` run exists for it *before* deleting
  `close_signoff`, not only after. Only delete first, without doing this, for a **past**
  business date — `SignoffRunner` never re-attempts a date other than today's, so there is
  no sweep that would silently re-sign it off.
- **Statements already generated from the bad sign-off are not deleted or edited** —
  docs/DOMAIN.md's statements are immutable, content-hashed
  objects. A correction is a new, clearly marked corrected statement, following whatever
  correction/reissue path Phase 9 or later defines — this runbook does not invent one.

#### Escalation

- Always escalate to the platform owner and compliance before deleting a
  `close_signoff` row — this is a decision with regulatory and participant-communication
  consequences, not a routine operational fix, and must be a deliberate, authorized act
  by someone who has confirmed the underlying discrepancy (never a reflexive response to
  a single alert).

### What must be reported, and to whom (both scenarios)

- **§1** (delayed sign-off): report to the platform owner once the blocking gate is
  identified — this alert exists to surface the delay, and the substantive report belongs
  to whichever gate's runbook is doing the diagnosis.
- **§2** (re-opened sign-off): always reported to the platform owner and compliance,
  including: the business date, the original `close_signoff` row (before deletion — copy
  it out first), the reconciliation evidence that justified re-opening it, and — if
  statements were already generated — how many and to whom, since that is now a
  participant-communication question, not just an internal-control one.

---

# Data stores and identity

<a id="postgres-backup-age"></a>

## Runbook — PostgreSQL Backup Age Exceeded

**Alert:** `PostgresBackupAgeTooHigh` (`infrastructure/observability/alerts/financial-control.rules.yml`).  
**Severity:** `page`  
**When to use this:** The CloudNativePG backup timestamp `cnpg_collector_last_available_backup_timestamp` has not updated in over 24 hours.

---

### Symptoms

- PagerDuty notification: `PostgresBackupAgeTooHigh`.
- Metric `(time() - max(cnpg_collector_last_available_backup_timestamp)) > 86400` has remained true for more than 15 minutes.
- The scheduled daily physical backup or continuous WAL archive to the object store (SeaweedFS, S3 API) is failing.

---

### What is at risk

**Recovery Point Objective (RPO) and Disaster Recovery.**  
If a catastrophic node or disk failure occurs on the database cluster, the platform cannot guarantee recovery of state within the mandated 24-hour RPO window. While TigerBeetle holds authoritative unit and cash balances, PostgreSQL holds organization profiles, plan versions, tax references, and CDC projections.

---

### First three diagnostic commands

1. **Check CloudNativePG Cluster and Backup status:**
   ```bash
   kubectl get cluster postgres -n fondaria -o yaml | grep -A 20 "backup:"
   kubectl get backup -n fondaria --sort-by=.metadata.creationTimestamp
   ```

2. **Inspect CloudNativePG Operator and Pod Logs:**
   ```bash
   kubectl logs -n fondaria -l app.kubernetes.io/name=cloudnative-pg --tail=100
   kubectl logs -n fondaria postgres-1 -c postgres --tail=100 | grep -i barman
   ```

3. **Verify Object Store (SeaweedFS / S3) Accessibility and Secret Credentials:**
   ```bash
   # Test connectivity and credentials against the backup bucket
   kubectl get secret postgres-backup-credentials -n fondaria -o json
   # Check the object store's S3 endpoint responds
   kubectl run test-s3 --rm -i --tty --image=curlimages/curl -- curl -I http://object-store:8333/healthz
   ```

---

### Decision criteria

| Finding | Cause | Action |
|---|---|---|
| `ScheduledBackup` failed with S3 auth error | Expired or invalid credentials in `object-store-credentials` | Rotate/reapply `OBJECT_STORE_ROOT_ACCESS_KEY` / `OBJECT_STORE_ROOT_SECRET_KEY` via ExternalSecret (then restart the `object-store` pod: it reads its admin identity at start-up) and trigger an immediate backup: `kubectl cnpg backup postgres --backup-name manual-fix-$(date +%s)`. |
| Object store volume full or network unreachable | Storage exhaustion or network policy blockage | Check the `object-store` PVC usage or the S3 endpoint routing. Check NetworkPolicy `object-store-policy` admits `postgres` on 8333. |
| WAL archiving stuck or backlog high | Local WAL disk pressure or archiver timeout | Inspect `/var/lib/postgresql/data/pg_wal`. CloudNativePG retains unarchived WALs; if storage is tight, temporarily scale disk or restart archiver process. |

---

### Triggering an Immediate On-Demand Backup

Once the root cause (credentials, network, or storage) is resolved, trigger an immediate backup to clear the alert:

```bash
kubectl apply -f - <<EOF
apiVersion: postgresql.cnpg.io/v1
kind: Backup
metadata:
  name: postgres-emergency-backup-$(date +%s)
  namespace: fondaria
spec:
  cluster:
    name: postgres
EOF
```

Monitor backup completion:
```bash
kubectl get backup -n fondaria -w
```
Confirm `cnpg_collector_last_available_backup_timestamp` updates in Prometheus, resolving the alert.

<a id="postgres-failover-and-pitr"></a>

## Runbook — PostgreSQL Primary Failover and Point-in-Time Recovery (PITR)

**Applies to:** CloudNativePG cluster `postgres` (`infrastructure/k8s/base/postgres/cluster.yaml`).  
**Purpose:** Standard operating procedure for verifying synchronous replication failover without data loss, and executing Point-in-Time Recovery (PITR) drills.

---

### 1. Synchronous Replication & Primary Failover

#### Architecture Invariants
- **1 primary + 2 synchronous standby replicas** (`instances: 3`, `minSyncReplicas: 1`, `maxSyncReplicas: 2`).
- Parameter `synchronous_commit: "on"`.
- A transaction commit returns success to the client application only after the WAL record has been flushed to disk on the primary AND synchronously acknowledged by at least one standby replica.
- **Failover zero-loss contract:** When the primary pod crashes or is terminated (`kubectl delete pod postgres-1`), CloudNativePG automatically promotes the most synchronized standby replica (`postgres-2` or `3`). No committed transactions are ever lost.

#### Failover Drill Verification Procedure
1. **Pre-failover health check:**
   ```bash
   kubectl cnpg status postgres -n fondaria
   ```
   Verify all 3 instances are ready and synchronous replication is active:
   ```
   Name:                postgres
   Primary:             postgres-1
   Instances:           3
   Ready instances:     3
   Synchronous standbys: postgres-2 (sync), postgres-3 (potential)
   ```

2. **Simulate Primary Node Failure / Pod Eviction:**
   ```bash
   # Delete the current primary pod forcefully
   PRIMARY=$(kubectl get cluster postgres -n fondaria -o jsonpath='{.status.currentPrimary}')
   kubectl delete pod ${PRIMARY} -n fondaria --now
   ```

3. **Verify Failover Progression:**
   ```bash
   kubectl get pods -n fondaria -w
   kubectl cnpg status postgres -n fondaria
   ```
   - CloudNativePG detects lost primary within seconds.
   - Demotes old primary and promotes `postgres-2` as the new primary.
   - Applications reconnect automatically via the read-write service (`postgres-rw`).
   - The deleted pod is recreated as a standby and rejoins the cluster without manual intervention.

---

### 2. Point-in-Time Recovery (PITR) Drill

#### Purpose
To restore the database state to an exact historical timestamp (e.g., prior to a catastrophic data corruption, mistaken batch drop, or regulatory audit request).

#### Prerequisites
- Continuous WAL archiving active via `barmanObjectStore` in the object store (SeaweedFS, S3 API) (`s3://fondaria-backups/postgres`).
- At least one full base backup created by `ScheduledBackup`.
- Designated recovery target timestamp (ISO 8601 UTC), e.g. `2026-09-09T14:30:00Z`.

#### PITR Execution Steps
1. **Identify Target Timestamp and Source Cluster:**
   ```bash
   TARGET_TIME="2026-09-09T14:30:00Z"
   SOURCE_CLUSTER="postgres"
   ```

2. **Deploy PITR Recovery Cluster:**
   Apply `infrastructure/k8s/base/postgres/pitr-restore-example.yaml` with the updated target timestamp:
   ```yaml
   apiVersion: postgresql.cnpg.io/v1
   kind: Cluster
   metadata:
     name: postgres-pitr-drill
     namespace: fondaria
   spec:
     instances: 1
     imageName: ghcr.io/cloudnative-pg/postgresql:17.2
     recoveryTarget:
       targetTime: "2026-09-09T14:30:00Z"
     bootstrap:
       recovery:
         source: postgres
     externalClusters:
       - name: postgres
         barmanObjectStore:
           destinationPath: "s3://fondaria-backups/postgres"
           endpointURL: "http://object-store:8333"
           s3Credentials:
             accessKeyId:
               name: postgres-backup-credentials
               key: ACCESS_KEY_ID
             secretAccessKey:
               name: postgres-backup-credentials
               key: SECRET_ACCESS_KEY
           wal:
             compression: gzip
     storage:
       size: 50Gi
       storageClass: tigerbeetle-nvme
   ```

3. **Monitor Restore Progress:**
   ```bash
   kubectl logs -n fondaria postgres-pitr-drill-1 -c postgres -f
   ```
   Verify logs:
   - Base backup fetched from the object store.
   - PostgreSQL enters recovery mode.
   - WAL files replayed from `barmanObjectStore` archive up to `recoveryTarget.targetTime`.
   - Recovery stops at target time: `recovery stopping at restore point or timestamp`.
   - Database accepts read-only or promoted read-write queries.

4. **Validate Restored State & Clean Up:**
   Connect via psql and assert table row counts and state match the target timestamp. Once verified, delete the drill cluster:
   ```bash
   kubectl delete cluster postgres-pitr-drill -n fondaria
   ```

<a id="jwks-outage"></a>

## Runbook — Edge JWKS fetch failing

**Alert:** `EdgeJwksFetchFailing` (`infrastructure/observability/alerts/edge-security.rules.yml`).
**When to use this.** `envoy_http_jwt_authn_jwks_fetch_failed` has increased more than 3 times in 5 minutes: `fondaria-gateway`'s edge JWT validation (Task 10.2, `securitypolicy-jwt.yaml`) cannot fetch Keycloak's current signing keys.

### Symptoms

- Page fires after `for: 1m` once the sustained-failure threshold is crossed — a single, isolated
  fetch failure (routine on proxy pod startup, before xDS config has converged) does not page.
- Every request to the participant API (`/v1/participants/*`) and employer API
  (`/v1/organizations/*`) returns `401` at the edge, regardless of whether the caller's token is
  valid — `contribution-api` and `balance-api` see nothing (their own request metrics stay flat).
- Only `httproute-keycloak.yaml` (Keycloak's own login/token/JWKS endpoints) is unaffected — that
  route carries no JWT policy, by design (a client cannot obtain a token from an endpoint that
  itself demands one).

### What is at risk

**Availability, not correctness.** This is a fail-closed control (Task 10.2's own posture): no
forged or unverifiable token can slip through while this fires. The cost is that legitimate
traffic is also refused — every participant and employer API call is unavailable for as long as
the gateway cannot verify a signature against a current key set.

### First three diagnostic commands

1. **Confirm Keycloak itself is up and serving its JWKS endpoint** (this is almost always the
   actual fault — the gateway's fetch failing is a symptom, not the cause):
   ```bash
   kubectl -n fondaria get pods -l app.kubernetes.io/name=keycloak
   # From any pod that can reach the cluster network (e.g. a transient debug pod in `fondaria`):
   curl -sf http://keycloak.fondaria.svc.cluster.local:8080/realms/fondaria/protocol/openid-connect/certs
   ```

2. **Read the proxy's own jwt_authn log lines** (Envoy logs the fetch failure and its cause —
   DNS, connection refused, timeout, or a non-200 response — at `warning`/`error` level):
   ```bash
   kubectl -n envoy-gateway-system logs -l gateway.envoyproxy.io/owning-gateway-name=fondaria-gateway -c envoy --tail=200 | grep -i jwks
   ```

3. **Check the `keycloak` Service and its NetworkPolicy** (Task 12.5's default-deny posture means
   a NetworkPolicy misconfiguration blocking `envoy-gateway-system` -> `fondaria` traffic looks
   identical to Keycloak being down from the gateway's point of view):
   ```bash
   kubectl -n fondaria get endpoints keycloak
   kubectl -n fondaria get networkpolicy keycloak-policy -o yaml
   ```

### Decision criteria

| Finding | Action |
|---|---|
| Keycloak pod not `Ready`, or its own logs show a crash/OOM | Restart/investigate Keycloak (`infrastructure/k8s/base/keycloak/`). The gateway will recover on its own once a fetch succeeds — no gateway-side action needed. |
| Keycloak is healthy, JWKS endpoint returns 200 from inside `fondaria`, but the gateway still fails | Network path issue between `envoy-gateway-system` and `fondaria` — check the `keycloak-policy` NetworkPolicy allows ingress from `envoy-gateway-system`, and Calico's own health. |
| JWKS endpoint returns non-200 or malformed JSON | A realm misconfiguration or a bad Keycloak deploy — check `infrastructure/k8s/base/keycloak/configmap.yaml`'s embedded realm against `infrastructure/keycloak/realm.json` (the base realm has no users and placeholder client secrets; `KeycloakRealmConfigMapParityTest` should already have caught any other divergence before this reached the cluster), and that the `keycloak-client-secrets` Secret exists (the pod does not start without it). |
| Everything above looks healthy and the alert is still firing | Check Envoy Gateway's own controller (`envoy-gateway` Deployment, `envoy-gateway-system`) — a stuck xDS push can leave a proxy pod running a stale/never-converged config indefinitely. |

### Escalation

Not a data-integrity or compliance incident by itself (no unverified token is ever accepted) —
treat it as an availability incident on the participant and employer APIs. Escalate to whoever
owns Keycloak/cluster networking if the first two diagnostics don't resolve it within the normal
availability incident window (``#slo-availability``).

<a id="break-glass"></a>

## Runbook — break-glass access active

**Alert:** `BreakGlassAccessActive` (`infrastructure/observability/alerts/financial-control.rules.yml`).
**When to use this.** `break_glass_active_count` is greater than zero (`severity: page`), meaning an internal operator has requested or executed a privileged staff action using the emergency break-glass procedure, bypassing dual approval.

### Symptoms

- Critical page alert fires immediately upon break-glass action creation/execution.
- `break_glass_active_count` is non-zero on the Prometheus metrics dashboard.
- A new entry exists in `workflow.staff_action` with `is_break_glass = true`.
- Structured audit logs record a `BREAK_GLASS_TRIGGERED` audit entry.

### What is at risk

**Participant funds integrity and unauthorized privilege escalation.**
Dual approval (four-eyes) is mandatory under normal conditions to prevent fraud, operator mistakes, or unilateral changes affecting participant money.
Break-glass access bypasses four-eyes controls for emergencies (e.g. system halting, severe bug mitigation, emergency kill switch activation). It must be verified immediately by the security and operations team.

### First three diagnostic commands

1. **Identify the break-glass action and the operator who invoked it:**
   ```sql
   SELECT action_id, action_type, description, control_totals,
          requested_by, requested_at, break_glass_reason, break_glass_expires_at, status
   FROM workflow.staff_action
   WHERE is_break_glass = true
   ORDER BY requested_at DESC
   LIMIT 5;
   ```

2. **Inspect the audit trail and hash chain:**
   ```sql
   SELECT sequence, action_id, action_type, event_type, actor, recorded_at, prev_hash, entry_hash
   FROM workflow.staff_action_audit
   WHERE action_id = '<action_id_from_step_1>'
   ORDER BY sequence ASC;
   ```

3. **Check application logs for the break-glass request:**
   ```bash
   kubectl -n core logs deployment/contribution-api --tail=200 | grep -i "break_glass"
   ```

### Decision criteria

| Finding | Action |
|---|---|
| Valid declared emergency with documented ticket and appropriate control totals | Contact the requesting operator to confirm status. Ensure action completes within `break_glass_expires_at` window. Schedule post-incident four-eyes review. |
| Expired break-glass window (`now() > break_glass_expires_at`) | Confirm the platform rejected execution. The active metric resets to 0 once the action settles. |
| Unrecognized operator or invalid/missing justification | Immediate security incident: revoke operator credentials in Keycloak, evaluate rollback of changes made, and initiate security response. |

### Escalation

- Notify Head of Operations and Information Security immediately.
- A mandatory post-mortem audit must review all stated control totals against actual ledger/database movements within 24 hours.

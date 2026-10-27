# Fondaria Platform Demo — Operator Runbook

> **Audience**: Demo presenter and platform operator.  
> **Purpose**: Setup, prerequisites, reset procedures, timing constraints, and failure recovery for the 7-beat live rehearsal and executive demonstration.

---

## 1. Prerequisites & Environment

Running the Fondaria demo locally simulates **300,000+ to 500,000 participants** across 12+ employers with 12 microservices, PostgreSQL (CloudNativePG), RabbitMQ, TigerBeetle, SeaweedFS (object store), Valkey, OpenTelemetry Collector, Prometheus, Loki, Tempo, and Grafana on a local Kind cluster.

### 1.1 Hardware & Docker Resource Limits
- **Docker Memory**: **12 GB minimum** assigned to Docker Desktop / Colima / OrbStack (16 GB recommended).
  - *Failure mode if < 12 GB*: TigerBeetle or Tempo experiences Linux OOM kills (`ExitCode 137`) during the 200,000-row onboard beat.
- **Docker CPUs**: 6 cores minimum (8 cores recommended).
- **Disk**: At least 30 GB free space.

### 1.2 Fixture Isolation Trap
Before starting the cluster or demo:
```bash
make test-fixtures-clean
```
*Why*: Any lingering Testcontainers from `mvn verify` or IDE unit test runs share Docker memory and socket bandwidth with Kind. Always prune orphaned containers before demo initialization.

### 1.3 Deployment Tooling: Skaffold Only
- **Rule**: Never run manual `kubectl apply -f ...` across service manifests.
- Deploy exclusively via:
  ```bash
  make skaffold-run
  ```
- *Why*: Skaffold orchestrates Jib container builds, digest calculation, Kustomize overlay selection (`infrastructure/k8s/overlays/local`), and sync waves. Hand-running `kubectl apply` bypasses ConfigMap generation and Linkerd sidecar injection annotations.
- **`make skaffold-run` reverts every manual experiment.** It re-applies the overlay, so a `kubectl patch` or `kubectl set env` made to test a setting (a consumer count, a pool size) is silently undone on the next run, and what you are measuring changes under you. Put the setting in the overlay, or re-apply it after every `skaffold-run` and write down that you did.
- **A re-applied Postgres or RabbitMQ restarts its pod.** The local overlay caps the shutdown at 60 s for both (row 26j; the operator defaults are 30 min for CloudNativePG and one week for RabbitMQ), so services crash-loop for about a minute and recover on their own. If a pod is still `Terminating` after two minutes, something else is wrong; `kubectl delete pod -n fondaria <pod> --grace-period=0 --force` gets you going, but find the cause afterwards.

### 1.4 The "Kubernetes Jobs Are Immutable" Trap
- Kubernetes `batch/v1` Jobs forbid mutating `spec.template`. If a Job definition changes or fails, re-applying causes:
  `The Job "..." is invalid: spec.template: field is immutable`
- *Mitigation*: The demo services run as stateless Deployments and long-running workers (`allotment-job`, `eod-job`, `statement-worker`, `projection-worker`). If any ad-hoc migration Job is run, always delete the existing job before re-submitting:
  ```bash
  kubectl delete job -n fondaria <job-name> --ignore-not-found
  ```

---

## 2. Cluster Setup & Reset (`make demo-reset`)

To guarantee identical starting conditions and pristine reconciliation state, execute a clean reset:

```bash
make demo-reset
```

What `make demo-reset` executes automatically:
1. `rm -rf demo/out`: Clears local manifest, NAV series logs, and fund flow state.
2. `make k8s-down`: Destroys the Kind cluster and local volumes.
3. `make k8s-up`: Recreates the Kind cluster with Linkerd service mesh and local storage.
4. `make skaffold-run`: Builds and deploys all 12 services and infrastructure containers.
5. `make demo-seed` (or `make demo-seed ORGS=8` to start with the 8 smallest employers, ~60,000 participants, and re-run without `ORGS` later to add the rest): Seeds 3 funds, 12 employers, ~300,000 participants, generates initial payroll files and cash receipts, strikes opening NAVs, and writes `demo/out/manifest.json`.

**Measured Duration**: ~4 minutes 15 seconds.

---

## 3. Background Port-Forwards

The three user-facing demo HTTP services run inside the cluster:
- **`contribution-api`**: port `30080` (plans, payroll batches, cash receipts)
- **`balance-api`**: port `30081` (balances, movements, organization summaries)
- **`valuation-api`**: port `30082` (valuation files, NAV strikes)

The `Makefile` targets (`demo-seed`, `demo-day`, `demo-onboard`) depend on `demo-port-forward`, which automatically ensures resilient background port-forwards are healthy.

To verify or start manually:
```bash
make demo-port-forward
```

To mint a valid Keycloak demo JWT token:
```bash
TOKEN=$(java -cp libs/demo-tools/target/classes:libs/platform-common/target/classes dev.fondaria.demo.DemoTools token)
```

---

## 4. Beat-by-Beat Troubleshooting & Recovery

### Beat 1: The Estate
- **Symptom**: `curl .../organizations/{id}/summary` returns connection refused or 401.
  - *Fix*: Check port-forward (`make demo-port-forward`) and verify token generation (`demo-tools token`).
- **Symptom**: Participant count < 300,000 in PostgreSQL.
  - *Query*:
    ```bash
    kubectl exec -n fondaria postgres-1 -c postgres -- env PGPASSWORD=$(kubectl get secret postgres-app -n fondaria -o jsonpath='{.data.password}' | base64 -d) psql -h postgres-rw -U funds -d funds -c "SELECT COUNT(*) FROM master.participant;"
    ```
  - *Fix*: If seed was interrupted, run `make demo-reset`.

### Beat 2 & Beat 3: A Day Passes & The NAV Falls
- **Symptom**: `demo-day` or `demo-tools valuations` fails with `VALUATION_ALREADY_LIVE`.
  - *Cause*: Attempting to strike NAV twice on the same simulated date without advancing the calendar.
  - *Fix*: Advance the date first:
    ```bash
    java -cp libs/demo-tools/target/classes:libs/platform-common/target/classes dev.fondaria.demo.DemoTools advance --business-days 1
    ```
- **Symptom**: `balance-api` shows stale NAV after strike.
  - *Cause*: `balance-api` in-memory NAV cache refreshes on a 10s convergence sweep (`fondaria.nav-cache.refresh-interval: 10s`).
  - *Action*: Wait 5-10 seconds for the scheduled sweep to refresh before displaying the endpoint result, or highlight this as the deliberate decoupled caching layer.

### Where to see the daily close and the statements
eod-job's close is a scheduled background process. In the demo overlays it waits for a close window: `CLOSE_WINDOW_START=17:00` on the simulated clock, with a 20 s tick. `advance` lands on 09:00, so nothing closes while the day's payroll and NAV strike are still landing. `make demo-day` now ends with `demo-tools close`, which moves the clock to 17:05 on the same business date (forward only, still before the 18:00 cut-off) and waits for `workflow.close_signoff`. It then prints the sign-off and the exact runs it cites: internal reconciliation (status, funds, holding and cash accounts, breaks, and how many passes the date needed), custodian reconciliation, the dense snapshot with its `s3://eod-snapshots/...` positions and manifest keys, and whether the manifest hash chain is intact. `make demo-eod` prints it again, or closes the day if `demo-day` was interrupted before its close. Statements are monthly: only the last business day's sign-off fans them out. On that date `close` goes on to wait for `workflow.statement_run` to reach `COMPLETED`, then prints the first organization's hero statement as a waterfall, read back from `s3://statement-objects/statements/period=YYYY-MM/participant=<participantId>.json`. `make demo-statements` gets there from any day: it advances to the month's last business day (or the next month's, if today already is one), runs that day, and closes it. In Grafana, **Financial Control** has a row for closes signed off, sign-off blocked or announce pending, statements rendered, and statement runs completed (all over the selected range). The **EOD Hash-Chain Validity** and **Reconciliation Run Status** panels sit above that row. Expect `EODReconciliationBlocked` to fire after an eod-job restart until the next close window: with the window set, no pass runs before then, and the alert is telling the truth.
- **Symptom**: `close` keeps printing `internal reconciliation found N break(s)`.
  - *Cause*: The projection is still catching up with the day's allotments. Every 20 s pass that does not tie is a new `FAILED` row, and the next pass retries. It settles within a minute or two of the last allotment.
  - *Fix*: If it lasts past the 15-minute timeout, check Financial Control's **Projection Drift** and **CDC Lag** panels and `kubectl logs deploy/eod-job`. A drift that does not shrink is a real break, not a demo artefact.

### Beat 4: Product Velocity (Live Plan Versioning)
- **Symptom**: `POST /v1/organizations/{id}/plans` returns 409 Conflict.
  - *Cause*: A plan version with the exact same `(organizationId, planId, version)` was already published.
  - *Fix*: Increment the `version` field (e.g. `version: 2`) or adjust `effectiveFrom` to today's date.

### Beat 6: Onboard 200,000 Employees
- **Symptom**: Polling shows `parts allotted: 0/49` and remains `STAGED`.
  - *Cause*: Head-of-line blocking if `allotment-job` batch size is too low, or if the dealing date has no struck NAV.
  - *Verification*: Confirm `FONDARIA_ALLOTMENT_BATCH_SIZE` is set to `200` in `allotment-job-config`.
- **Symptom**: Nothing ever allots — every batch stays `STAGED` indefinitely, `allotment-job` logs `Allotment failed for batch …: TigerBeetle call failed` once per batch (~one every 6 s, easy to miss: the container log rolls over every ~15 s under the idempotent-create warnings), and the `tigerbeetle-0` log shows `create_accounts` and `lookup_transfers` but never a single `create_transfers`.
  - *Cause*: The replica is running `--development` without `--experimental --limit-request=1MiB`. TigerBeetle 0.17's development mode caps requests at 32 KiB (~253 transfers), the client learns that at registration, and every allotment chain (up to 8,189 transfers) is refused client-side with `TooMuchData` in ~2 ms — it never reaches the replica. Unit tests don't catch it because Testcontainers starts TigerBeetle without `--development`. Full trail: `TASK-LOG.md` step 99.
  - *Verification*: `kubectl -n fondaria exec tigerbeetle-0 -c tigerbeetle -- cat /proc/1/cmdline | tr '\0' ' '` must not show `--development` (row 26q, `ADR-0033` amendment: the local overlay no longer sets `TIGERBEETLE_DEVELOPMENT`). If it does, the replica is on a stale overlay; the `start.sh` dev-mode branch still pins `--limit-request=1MiB`.
  - *Fix*: redeploy with `make skaffold-run` (pinned by `TigerBeetleStatefulSetValidationTest#localEnvironmentsRunWithoutDevelopmentMode`). The stuck batches allot on the next sweeps — nothing to reset.
  - *Fix*: Run valuations for the current simulated date:
    ```bash
    java -cp libs/demo-tools/target/classes:libs/platform-common/target/classes dev.fondaria.demo.DemoTools valuations
    ```

### Beat 7: The Controls Are Real (Pod Kill & Recovery)
- **Action**: Delete `projection-worker` pod while CDC events are processing:
  ```bash
  kubectl delete pod -n fondaria -l app.kubernetes.io/name=projection-worker
  ```
- **Expected Recovery Time**: **~18 seconds** until the replacement pod achieves `Running (2/2)` and `Ready`.
- **Proof of Zero Loss**: RabbitMQ unacknowledged messages re-queue; `projection-worker` resumes consuming; the redelivered messages are idempotent no-ops, and the new pod's consumers get their own rows in `projection.projection_consumer_checkpoint` (the killed pod's rows age out).

# Design decisions

This document is the condensed record of why Fondaria is built the way it is. It replaces the individual architecture decision records (ADRs) and the early design papers. Code comments, migrations and tests still cite decisions as `ADR-00NN`, so each decision keeps its number as a heading below and the numbers will not be reused.

Read [DOMAIN.md](DOMAIN.md) first for what the platform does, and [RUNBOOKS.md](RUNBOOKS.md) for how it is operated.

**Contents**

1. [Architecture principles](#architecture-principles)
2. [The system in one page](#the-system-in-one-page)
3. [Decision log](#decision-log)
4. [Questions still open](#questions-still-open)

---

## Architecture principles

| # | Principle | Consequence |
|---|---|---|
| P1 | **One source of truth for money.** | Balances come from the ledger, never from a cache or a SQL sum. Everything else is a projection that can be rebuilt. |
| P2 | **No dual writes.** | A request handler records one fact in one system of record. Changes propagate by change data capture (CDC) from that system. |
| P3 | **Every consumer is idempotent.** | Delivery is at-least-once everywhere. Correctness comes from idempotent writes keyed on the ledger's `transfer_id`. |
| P4 | **Money is integers.** | Cash is `long` cents. Units and NAV are `long` at scale 1e-8. No `double`, `float` or `BigDecimal` outside display. |
| P5 | **Append-only.** | A financial fact is never updated or deleted. A correction is a new row. |
| P6 | **Cloud-agnostic.** | No cloud-provider SDKs. Object storage is reached through the S3 API only. (ADR-0006 records the one deliberate exception.) |
| P7 | **Security at the edge, authorization in the app.** | The gateway and mesh do TLS and token validation. The application does consent, scope and business-rule checks. |
| P8 | **Rebuildability is a tested feature.** | Dropping the projection database and rebuilding it from the ledger is an exercised procedure. |

Two further rules run through the decisions below:

- **Correctness of participants' money outranks speed, elegance and preference.**
- **A rule that protects a balance is enforced where the write serializes**, inside the ledger engine. Product policy (vesting, caps, tax) lives above it, as effective-dated data.

## The system in one page

```
employer ──file + wire──► contribution-api ──► TigerBeetle (ledger) ◄── balance-api ◄── participant / employer
                                │                    │
                       allotment-job (prices units)  │  native CDC job
                                                     ▼
                                                 RabbitMQ ──► projection-worker ──► PostgreSQL (projection)
                                                                                        │
              valuation-api (NAV in) ──► NAV store (+ cache)          eod-job (daily close) ──► statement-worker
```

| Concern | Technology |
|---|---|
| Ledger | TigerBeetle 0.17.x, reached only through `LedgerPort` |
| Broker and CDC | RabbitMQ 4.x; TigerBeetle's native `amqp` CDC job |
| Warm store | PostgreSQL 17 (JDBC, `JdbcClient`), Flyway 11 migrations |
| Cache | Valkey 8 via Lettuce; integer-only Lua scripts |
| Object store | SeaweedFS (S3 API only) |
| Identity and edge | Keycloak 26, Envoy Gateway (Gateway API), Linkerd |
| Runtime | Java 21, Spring Boot 3.5, Spring MVC on virtual threads; Kubernetes |
| Tests | JUnit 5, AssertJ, Testcontainers, jqwik |

PostgreSQL has four schemas with different rules (ADR-0011): `projection` (CDC-fed history, never written by a service), `master` (organizations, participants, plans), `reference` (effective-dated data and rule-evaluation evidence, no update or delete) and `workflow` (service-owned status lifecycles).

---

## Decision log

Statuses: **Accepted**, **Amended** (still in force, with changes noted), **Superseded** (replaced; kept for history).

### ADR-0001 — Concurrency model: Spring MVC with virtual threads
*Accepted.* Services are I/O-bound and TigerBeetle's Java client is blocking, so WebFlux would only wrap blocking calls in a thread pool while paying the full cost of reactive code. Blocking Spring MVC with `spring.threads.virtual.enabled=true` and JDBC is used throughout. WebFlux, Reactor and R2DBC are prohibited without a superseding decision. Carrier-thread pinning is the risk to watch: no `synchronized` around I/O (use `ReentrantLock`), and `jdk.VirtualThreadPinned` events are monitored.

### ADR-0002 — Event broker: Redpanda
*Superseded by ADR-0022.* Chose a Kafka-compatible broker beside RabbitMQ for independent consumer cursors. The cost, two brokers, turned out larger than the benefit.

### ADR-0003 — Use TigerBeetle native CDC; no custom ledger poller
*Amended by ADR-0007.* A bespoke poller with leader election and cursor storage was rejected in favour of TigerBeetle's own `tigerbeetle amqp` CDC job, which is stateless and resumes from the last acknowledged event. Accepted risk: if the CDC job is down, only projection freshness suffers, never money. Guarded by a CDC lag SLO (warn 30 s, page 300 s).

### ADR-0004 — Product scope and vesting model
*Amended by ADR-0012 (vehicle clause only).* Employee and employer contributions are separate lots from the moment they arrive and are never merged. Vesting is configurable per organization. Employer units are issued once, at contribution, into an *unvested* holding account and transferred to *vested* when they vest: vesting changes entitlement, not ownership, so returns accrue from day one. (Issuing units at the vesting date was rejected: it would deny participants the return on employer money during vesting, and cannot be unwound.) NAV is a scripted series for v1 behind a `ValuationSource` port. Tax is out of scope for v1, but the product model declares `taxCharacter`, `permanence` and `earlyWithdrawalTreatment`.

### ADR-0005 — Tenancy partitioning and ledger cluster resolution
*Amended (2026-08-28).*
- **Postgres and messages are partitioned tenant-first**, `(tenant_id, participant_id)`, for locality and so tenant scoping can be row-level security. No Postgres sharding; the escalation ladder is batch inserts → `COPY` → partitioning → separate projection database → only then sharding.
- **The ledger is partitioned by fund, never by tenant.** The invariant `Σ participant units == units in issue` is per fund and funds are pooled across organizations; splitting a tenant onto its own cluster would turn an engine-enforced guarantee into an application-level one. `LedgerPort` resolves a cluster by fund and rejects any transfer whose legs resolve to different clusters. Very large tenants get a *segregated fund*, which carries its own invariant.
- **Amendment:** the tenant reaches the CDC stream in the ledger record's `user_data_64` slot, as the high 64 bits of a name-based UUID of the organization. Every movement carries its causing tenant; only pooled accounts carry none.

### ADR-0006 — Karpenter as an accepted deviation from cloud-agnosticism
*Accepted.* Bursty statement fan-out needs autoscaling that reacts in seconds, and Karpenter is EKS-native. This is a recorded exception to P6. Cluster Autoscaler is the portable fallback, and anything Karpenter-specific must degrade to it with a manifest swap, not a code change.

### ADR-0007 — CDC job warm standby via the RabbitMQ locker queue
*Accepted; corrects ADR-0003.* Exclusivity of the CDC job is enforced by an exclusive RabbitMQ "locker" queue, not by TigerBeetle. Running two replicas is therefore safe (only one can stream) and cuts recovery to a short retry interval. The standby wraps the job in a fast, capped retry loop rather than Kubernetes' growing crash-loop backoff. A RabbitMQ outage still stops CDC regardless of replica count.

### ADR-0008 — PostgreSQL is a projection, not a second ledger
*Amended by ADR-0011 (§2 only).* A second, PostgreSQL-backed `LedgerPort` was built and rejected. There is exactly one implementation (TigerBeetle). Two implementations of the same integrity rules can only stay correct if a conformance suite stays perfectly current forever, and a divergence is a silent, money-affecting bug.
- Balance floors, id idempotency, linked-chain atomicity and the pending lifecycle are enforced inside the engine, atomic with the write. Checking above the port reintroduces read-then-write races.
- Vesting, caps, ISR and UMA are product policy and belong above the port, in `libs/rules-engine`, as versioned data.
- **An adapter is a thin translator, never a rule engine.** It maps the engine's `ExceedsCredits` to `INSUFFICIENT_UNITS`; it never computes whether a balance suffices.
- The swap path if TigerBeetle must ever be replaced is the vendor-neutral port plus the CDC history, not a parallel shadow ledger.

### ADR-0009 — Contribution ingestion: file by reference, processed asynchronously
*Amended.* Payroll files can run to hundreds of thousands of rows. The employer requests an upload slot, uploads the file directly to object storage (bytes never pass through the API), then submits it by reference with its SHA-256. The API answers `202 Accepted`; validation runs in the background. Submission is content-addressed on `(organizationId, objectKey, checksum)`, so resubmitting the same file is the same batch. The file header is the single source of truth for its own content. Whole-batch rejection covers structural failures only (checksum, malformed file, organization mismatch, row-count and subtotal/total mismatches); per-row plan-rule failures are layered on top. Payroll-vendor connectors and direct API push are deferred.

### ADR-0010 — Cash receipt is triggered by a manual reconciliation entry
*Accepted.* The ledger posts `CASH_RECEIPT` when an operator keys in a wire seen on the bank statement. The control is not weakened, only its input is manual: the money is a real ledger balance, and the file-total-equals-cash gate still blocks allotment to the cent. The entry is idempotent on the ledger via a transfer id derived from an idempotency key. Cash and file are independent arrivals and either can come first or never. Known weaknesses: manual entry can be wrong (mitigated by compensating `CORRECTION` entries, stored bank references and the gate; maker/checker is the first thing to add) and it does not scale past a few organizations' worth of volume. Bank-statement integration is an open question.

### ADR-0011 — Where non-ledger data lives
*Accepted.* PostgreSQL has more than one role, separated by schema and enforced by database grants (`projection`, `master`, `reference`, `workflow`, as above). ADR-0008's real invariants are restated: balances never read from PostgreSQL, no ledger rule enforced there, one `LedgerPort`, and `projection` is never written by a service. "One system of record per handler" governs writing the *same fact* twice; recording two *different* facts (an operator asserted a wire exists; money moved) is allowed if the non-ledger fact is written first, both writes are idempotent on derived keys, and a crash between them leaves a row whose status says the movement is unconfirmed. The ledger holds money and links only, never descriptive data; its `user_data_128` carries a SHA-derived reference.

### ADR-0012 — v1 ships two vehicles
*Accepted; supersedes ADR-0004's vehicle clause.* v1 supports *caja de ahorro* and an employer-sponsored retirement fund with vesting. *Fondo de ahorro*, personal PPR and high-yield savings remain deferred but must need configuration, not schema change. The contribution file does not change; `employmentStartDate` becomes load-bearing for graded vesting.

### ADR-0013 — Display rounding of a valued holding
*Accepted.* **Round per lot, then sum.** Every rendered total is the exact sum of the parts rendered beside it. This is a display rule only; nothing is created or destroyed. The reference implementation is `FundPosition.of`.

### ADR-0014 — Rounding granularity follows the rendered surface
*Accepted.* Round at the granularity the surface renders, and no finer: per lot where lots are shown, per fund where one figure per fund is shown, and never again for totals. The same holding can differ by one centavo between two endpoints; each is internally consistent.

### ADR-0015 — An aggregate ties to the balances it aggregates
*Accepted.* An aggregate across holders (for example an organization summary) is the sum of the holders' own rounded figures, because its job is to reconcile with what each participant was told. `totalUnits × NAV` may therefore differ from the total value by up to half a centavo per lot, and this is documented in the API contract.

### ADR-0016 — Cross-service end-to-end tests live in one module
*Accepted.* `libs/e2e-tests` holds every test spanning more than one service; no service depends on another at any scope. The module ships nothing and is test-scoped. Spring Boot repackaging therefore uses an `exec` classifier so the plain jar stays resolvable. Its tests are tagged `integration` and run under `make verify-all`.

### ADR-0017 — The batch is the unit of one linked chain
*Accepted.* A file too large for one TigerBeetle linked chain is divided into **parts** at staging time, each part its own batch row and one atomic chain. The submitted batch is part 1. The file remains the unit of cash (one wire, one reconciliation gate on the submitted row); each part draws its own share of employer collections as it posts, so a partly-allotted file leaves exactly the unallotted remainder. Parts are sized by transfers (a budget of unit legs) with a row cap alongside. Cost: one file is no longer one batch for the employer, and the rounding residue of a file is bounded by the sum of its parts' ceilings (about a quarter of a peso across 200,000 participants). Every centavo is either units in issue or a recorded residual row.

### ADR-0018 — The daily close is one service
*Accepted.* `eod-job` hosts reconciliation, custodian reconciliation, the snapshot and sign-off as packages, because they are ordered by a gate rather than a queue, share one business date and calendar, and do not scale independently. The close never writes to the ledger: a reconciliation able to post could make itself pass. A later split is a package move.

*Amended: the close works on the earliest unsigned business date first.* At first every pass closed the business date the clock fell in, so a date not signed off by its 18:00 cut-off was abandoned. On the 2026-10-27 local load run, 2026-11-03 had breaks at 17:58 only because the projection was still draining. It tied at 18:03 and was never signed off. Now the date being closed (`CloseWindow.closingDate`) is the business date after the latest `close_signoff` row, or today if that is earlier or nothing has been signed off. All four passes work on it, outside the time window if it is a past date, and the next date does not start until it signs off. That keeps the manifest chain linear: N+1's snapshot cannot be taken while N is unsigned, so it always links to N's manifest. It never skips past a hole that a later catch-up of N would fork. Cost: reconciliation and the snapshot's tie read the ledger live (`LedgerPort` has no as-of read), so a past date can tie only until the next date's first unit movement posts. After that the close halts on it. `EODMissedSignoff` pages, `eod_close_dates_behind` counts the dates waiting, and an operator decides (RUNBOOKS "missed sign-off"). A halt was chosen over skipping the date, because a gap in what the platform can prove is worse than downtime. Dates behind the latest sign-off are never revisited, so deleting a past sign-off on purpose still holds.

### ADR-0019 — `ProviderAccount` scope carries omnibus vs. segregated
*Accepted.* Custody structure is an attribute of the account at the provider, not a platform mode. A fund has one or more `ProviderAccount` rows scoped `ALL_PARTICIPANTS` (omnibus, v1) or `ORGANIZATION(id)` (segregated, later), effective-dated in the `reference` schema. Reconciliation runs per provider account. Cash stays fund-level in v1. Not solved: there is no way to *retire* a provider account yet, and moving an employer to segregated custody is an operational event outside the platform.

### ADR-0020 — The daily snapshot is a hashed file, not a lakehouse table
*Accepted.* The snapshot is gzipped NDJSON in plain object storage with a manifest, partitioned by date. Immutability is enforced by the pass (a re-run re-derives and compares hashes; a mismatch halts and records `FAILED`), which is weaker than object lock and stated as such. Each manifest links to the previous date's because the close takes dates in order (ADR-0018, amended). The identity of a snapshot is the SHA-256 of the *uncompressed* content, so a JDK upgrade cannot make yesterday look corrupt. Row order comes from SQL `ORDER BY`; the manifest carries no timestamp. A partial unique index allows one snapshot of record per date. Cost basis is units at the price they were issued at, bounded by the time a lot was *posted*, not the date its NAV was struck for.

### ADR-0021 — Statement generation is a fan-out worker pool; sign-off is a domain event
*Accepted.* When all close gates pass, `eod-job` writes one `close_signoff` row and publishes `close.signed-off.v1` (row first, event derived, `announced_at` null until confirmed). Statements are rendered by a separate always-on, autoscaled `statement-worker` service, not folded into the batch service. Completion is counted in Valkey with integer operations, with a durable run record in PostgreSQL. A monthly statement is computed from month-boundary snapshots: opening, contributions by source, and market movement as the signed, unclamped plug.

### ADR-0022 — One broker, an OSI-licensed cache, before Kubernetes
*Accepted.*
- Redis becomes **Valkey** (BSD-3, wire-compatible); no data migration and no environment-variable renames.
- **Redpanda is removed; RabbitMQ is the only broker.** RabbitMQ was unavoidable (TigerBeetle's CDC publishes AMQP only), replay comes from TigerBeetle rather than a retained topic, and the independent-cursor benefit did not justify a second broker.
- Per-account ordering initially relied on RabbitMQ's consistent-hash exchange, with order-independence recorded as a standing requirement (delivered by ADR-0024).
- The `archive-worker` is written rather than relying on a paid broker feature; it is not part of the demo.
- Object storage was kept on the S3 API as the portability boundary (later revisited in ADR-0031).

### ADR-0023 — CI runs inside a fixed minute budget
*Accepted.* Pull requests and pushes to `main` run `verify` and migration checks only, skipped for docs-only changes. The integration suite runs on a twice-weekly schedule and on demand; the dependency scan runs weekly. A local `pre-push` hook runs `make verify` and the format check. Cost: an integration-only defect can sit on `main` for a few days. Revisit when the minute budget stops being the constraint.

### ADR-0024 — The participant-units hash chain repairs itself under out-of-order delivery
*Accepted.* Instead of depending on broker topology for ordering, the projection repairs the hash chain forward on write, inside the same transaction: after writing a row it re-links any successor whose previous-hash no longer matches, stopping at the first consistent link. A transaction-scoped advisory lock keyed on `(tenant, participant, fund)` serializes one holding without serializing different holdings. Tamper evidence is unchanged. A misconfigured exchange is now a throughput concern, not a correctness one.

### ADR-0025 — Kubernetes wiring: upstream operators, operator-generated credentials, IP-literal TigerBeetle addressing
*Accepted.* Operators (CloudNativePG, RabbitMQ, Calico, metrics-server) are upstream release manifests pinned by version and applied verbatim. Database and broker credentials are generated by the operators; only a few platform secrets remain in a secret store. TigerBeetle replicas are addressed by fixed ClusterIP literals through one Service per replica, and every client carries the full list. Workloads that link the TigerBeetle client run with an unconfined seccomp profile (it needs `io_uring`), carved out by name in policy. HPAs scale on CPU only. Queues are classic, not quorum, because TigerBeetle's connector declares its internal queues untyped and reads them with `basic_get`.

### ADR-0026 — Keycloak on Kubernetes; unsigned tokens trusted until the edge gateway existed
*Accepted; the known gap it recorded was closed when the JWKS-verifying edge gateway was built.* Keycloak is deployed with a realm import so clients receive real signed tokens. Keycloak's Service is `ClusterIP`-only; external token traffic goes through the gateway.

### ADR-0027 — Participant identity is captured now, envelope-encrypted per participant
*Accepted.* Each participant gets a random 256-bit data key that encrypts RFC, CURP, name and date of birth with AES-256-GCM (additional authenticated data binds ciphertext to its row and column); the data key is wrapped under one master key from the secret store, which production refuses to start without. **Crypto-shredding destroys only that participant's wrapped key**, leaving every other row and every ledger balance untouched. Identity lives in a companion table so the pseudonymous participant row is unchanged. Date of birth is required only for retirement vehicles, and both the input and the outcome are stored. Destroying a key is restricted to internal operations or services; capturing identity over an already-destroyed one is refused, so an erasure cannot be silently reversed.

### ADR-0028 — Gateway: Envoy Gateway on Gateway API core resources
*Accepted.* Routing uses Gateway API core resources (`GatewayClass`, `Gateway`, `HTTPRoute`) wherever they can express the rule, with vendor CRDs only for service type and traffic policy, so the implementation stays swappable. Participant, employer and identity hostnames each get a listener. **The internal API has no listener and no route**: it is unreachable because nothing names it as a backend, not because a rule denies it. Two narrow internal paths remain reachable through a prefix match and are default-denied in the application instead.

### ADR-0029 — Service mesh mTLS: what is meshed
*Accepted.* Every application-tier workload and the gateway data plane are meshed with a default-deny inbound policy; one `Server` plus `AuthorizationPolicy` per real call path. Datastores (Postgres, TigerBeetle, RabbitMQ, Valkey, object store) and the CDC job stay on NetworkPolicy only: meshing them adds a moving part to a money path for no measurable benefit. The edge identity is matched by a service-account pattern because the gateway's generated name is not stable.

### ADR-0030 — Storage traffic encryption: Calico WireGuard
*Accepted.* Cluster-wide WireGuard encrypts pod traffic that crosses nodes, including every connection to an unmeshed datastore, with no workload changes. Limits stated plainly: it does not cover two pods on the same node, and it provides encryption, not per-service authentication (NetworkPolicy still decides who can connect). It was verified on a single-node cluster only and must be confirmed on a multi-node cluster before production use. Per-datastore TLS remains a separate follow-up. *Amended 2026-10-22:* on GKE (ADR-0036), where Calico cannot be the dataplane, the same control comes from Dataplane V2's inter-node transparent encryption (WireGuard between every pair of nodes, `--in-transit-encryption=inter-node-transparent`); it does not cover hostNetwork pods or NodePorts, and the GKE overlay has neither. That environment is the first multi-node cluster this control runs on, and `infrastructure/gke/README.md` checks `Encryption: Wireguard` on every run; it verifies the GKE equivalent, not Calico's own implementation, which still needs its own multi-node check.

### ADR-0031 — Object store: SeaweedFS replaces MinIO
*Accepted.* Chosen for licence (Apache 2.0) and because the community MinIO images became unreliable to pull. One pinned image serves the server, bucket provisioning and tests. No service code changes, because services use the S3 client with vendor-neutral `OBJECT_STORE_*` variables. Infrastructure names become vendor-neutral too. No data migration was needed. The one heavy S3 client that is not ours is Postgres backup (barman-cloud multipart uploads), which must be shown working before point-in-time recovery counts as verified. Garage (AGPL), RustFS (not production-ready), Ceph and Ozone were rejected.

### ADR-0032 — Projection-worker scale-out: competing consumers without races
*Accepted.*
- **Checkpoint per consumer.** One row per consumer, so each row has one writer and no lock contention. The projection "head" is the minimum over recently active consumers and is a *monitoring estimate*, not a gap-free watermark; it is never in a correctness path.
- **Halt is cluster-wide.** A poison message sets a durable flag in `workflow.projection_halt` before the dead-letter publish; every replica polls it and stops acknowledging within about a second. The flag survives a projection rebuild and is cleared by an operator, then the deployment is restarted. Resuming is never automatic.
- **Connections are the binding constraint**, so consumers per queue, prefetch and pool sizes are tuned from measurement.
- Alerts aggregate with `max()` across pods, so N pods raise one alert; a new `projection_halted` gauge pages once for a cluster halt.

### ADR-0033 — TigerBeetle client budget, and ledger work sent in batches
*Accepted.* Ledger calls are batched, never one per item (`createAccounts` takes a list), and a loop of ledger or database calls over a batch is a defect. TigerBeetle has a fixed number of client slots; every overlay, rendered, must fit all ledger-client pods (HPA maximum plus rollout surge) inside the production limit minus a small headroom, enforced by a validation test that finds ledger clients through their ConfigMaps. The consequence is a cap on scale-out per cluster; more read capacity needs a second cluster (placed by fund) or a shared ledger-access tier.

### ADR-0034 — Batched projection consumption
*Accepted; later amended.* The projection consumes in batches (default 200 messages or 100 ms) and commits **one transaction per batch**, acknowledging once after commit. Transfers are inserted idempotently in id order; entries are projected in holding order so concurrent batches take advisory locks in the same order. A message that will not decode is isolated exactly as before: everything before it is committed and acknowledged, it triggers the cluster halt, and everything after it is returned to the queue. Transient failures roll back and retry the whole batch with bounded backoff; nothing is ever skipped.

### ADR-0035 — Projection activity: per-queue consumption metrics, ownership, a periodic summary
*Accepted.* An end-to-end timeout caused by a projection backlog (200,000 rows' worth of CDC messages still draining) left no trace: the worker logged "consumers started" and nothing after it, and queue depth existed only in the broker's Prometheus series, which a Testcontainers run does not have. projection-worker now exports `projection_batches_total{queue}` and `projection_messages_total{queue}` (counted after the ack, registered at zero on startup) and `projection_queue_owned{queue}` (0/1, entries queues only; the transfers queue has competing consumers on every pod), labelled only with configured queue names. It logs at INFO when it acquires or releases an entries queue's exclusive consumer (a refused standby logs nothing), and once every `PROJECTION_ACTIVITY_LOG_INTERVAL` (default 1 m) one summary line per pod: batches and messages per queue, entries queues owned N/M, its CDC lag, each projection queue's ready depth, and the queues it consumes from that hold messages it committed none of ("not draining"). The depth comes from a passive `queue.declare` on a virtual thread, abandoned after 5 s and reported as `unavailable`, never zero. The worker does not export depth: the broker's metric stays the one source for alerts and dashboards. The lines carry queue names, counts, a lag and exception type names only. No alert changes; the Financial Control dashboard gains backlog, messages-per-second and owners panels.

### ADR-0036 — A throwaway GKE environment for load tests
*Accepted.* Every load test had run on a single-node kind cluster, so no packet ever left the machine: cross-node and cross-zone latency on Postgres synchronous replication, TigerBeetle consensus and RabbitMQ quorum writes, real topology spread, the mesh between nodes and webhooks reached from a separate control plane were never exercised, and the prod overlay had never run. One large VM running kind was rejected for exactly that reason. Instead, `infrastructure/gke/` (`make gke-up` / `gke-deploy` / `gke-tunnel` / `gke-status` / `gke-down`) creates a GKE Standard cluster in a **new Google Cloud project per run and deletes it with the project**.
- **Shape.** A zonal control plane (free tier); nodes across three zones: a tainted `stateful` pool of 6 (one per TigerBeetle replica) and a `stateless` pool of 3, labelled and tainted with the prod overlay's `fondaria.example/node-pool`. Sizes in `infrastructure/gke/config.env`; about USD 2 an hour at the defaults.
- **Manifests.** `overlays/gke` is `overlays/prod` plus only what a bare cluster cannot supply: generated random Secrets in place of ExternalSecrets (never the local overlay's demo literals), a self-signed gateway TLS pair in place of cert-manager's ACME Certificate, the `tigerbeetle-nvme` StorageClass mapped to `pd-ssd` with `reclaimPolicy: Delete` (so **ledger numbers from this environment are a pessimistic bound, not production's**), a `ClusterIP` gateway Service, and the local overlay's demo clock and `pg_stat_statements`.
- **Network.** The services range is `10.96.0.0/16` because the base pins TigerBeetle to ClusterIPs `10.96.100.100`–`105`; a firewall rule opens 8443 and 9443 from the control-plane range for the admission webhooks. In-transit encryption is Dataplane V2 WireGuard (ADR-0030 amendment); Linkerd still meshes the application tier (ADR-0029).
- **Security posture.** Only Compute, GKE, Artifact Registry, IAM, Cloud Scheduler, Resource Manager and Billing Budgets are enabled; no AI/LLM API and no API key, ever. A Cloud Scheduler dead man's switch, created before anything billable, deletes the whole project `GKE_TTL_MINUTES` after `gke-up` starts (5 h at first; see the 2026-10-28 amendment), as an account holding only `projectDeleter`. A billing-account budget alert (alerts, does not cap). Private nodes, Cloud NAT egress, no SSH, control plane open only to the operator's current IP. Nodes run as a pull-only service account hidden from pods by Workload Identity. `gke-down` deletes the project, the kubeconfig entry and the gcloud login.
- Autopilot (no taints or per-node anti-affinity, no inter-node encryption), a regional control plane and local SSD for TigerBeetle were rejected or deferred. `make verify-k8s` renders the overlay and `GkeOverlayValidationTest` asserts the prod topology, no public exposure, generated credentials, the storage class and the services range; a new ExternalSecret in the base needs a matching generated Secret here.
- *Amended 2026-10-26 (first live run, on the old expark repository):* `up.sh` picks its placement (regions × machine families, every applicable quota checked, one throwaway VM per zone, since Google reports a zone out of capacity only on create) and deletes the auto-created `default` network (SSH/RDP open to the internet). A **`small` profile** (`GKE_PROFILE=small`, `overlays/gke-small`) fits a new billing account's default quota (12 vCPU, 250 GB SSD): 3 nodes, three copies of every datastore (TigerBeetle 3 replicas), lowered requests, observability and object store on pd-standard; it tests behaviour across machines, not capacity. Fixed in `overlays/gke` for what kind never exercised: NodeLocal DNSCache needs its own DNS egress rule; `unmeshed-cluster-callers` must name GKE's pod range, not Calico's; Postgres `max_connections: 400`, because the services' pools at prod's minimum replicas already exceed the base's 200. Still open: `tigerbeetle-cdc`'s warm standby is never Ready, so its rollout never completes. **A kept project** (`GKE_PROJECT`, the owner's choice for a project created by hand): the switch unlinks the project's billing instead of deleting it, as an account holding only `billing.projectManager` on it, and `gke-down` deletes the cluster, its orphaned volume disks, NAT, network, registry, scheduler job and service accounts, then unlinks billing.
- *Amended 2026-10-28 (sizing for the 300k-participant demo load, and a short window on big hardware):* the full profile's pools are sized separately. The stateful pool is 6 × `n2-standard-8`: Postgres gets 4 guaranteed CPUs and 8 GiB with `shared_buffers` 2 GB, and RabbitMQ requests 2 CPUs (limit 3) and 4 GiB. Locally Postgres was capped at 2 CPUs and RabbitMQ at 1 CPU; both were throttled for most of every drain. The stateless pool stays 3 × `n2-standard-4`, 60 vCPU in all; `gke-small` keeps the base limits. The owner can afford that size only briefly, so the flow minimises time on it. `gke-up` builds only the stateless pool. `gke-deploy` builds and pushes the images, *then* creates the stateful pool (`stateful-pool.sh`), deploys, and prints the cost window: vCPUs, start time, and the switch's deadline. `gke-down` prints how long the stateful pool billed. The dead man's switch is unchanged in what it does, but now counts minutes. It defaults to 120 minutes from `gke-up` (was 5 h) and is re-armed to 90 minutes when the stateful pool is created, before the pool exists, so the big machines never bill under a later deadline. Quota and zone capacity are still checked for both pools at `gke-up`, so a short quota cannot strand a half-built environment.

### ADR-0037 — Startup ordering: every workload waits for what it depends on, and readiness reflects consumption
*Accepted.* The first GKE run (ADR-0036) started things in whatever order the scheduler chose. `amqp-fanout` came up before `bridge.ledger` existed, its listener container stopped for good while the pod stayed Ready, and the projection received nothing (dashboards showed zero AUM); `object-store-buckets` used up its retries while cluster DNS was still broken. On kind the races were won by luck.
- **Init containers, in the base.** Each workload gets one `wait-for-<dependency>` init container per dependency its containers reference (Postgres, RabbitMQ, Valkey, the object store, TigerBeetle), running a script from the `startup-waits` ConfigMap. Every overlay inherits them. A wait checks that the dependency is usable, not that a port is open: a `SELECT 1` as the application's role, an authenticated `PING`, a `HEAD` on each bucket the service names, the RabbitMQ vhost through the management API with the operator's credentials. TigerBeetle has no health endpoint, so its wait needs a majority of the replica list to accept a TCP connection. Each wait reads its target from the ConfigMap key the service itself connects with, so the two cannot drift. Every call has its own timeout. After `WAIT_TIMEOUT_SECONDS` (15 min) the wait exits non-zero, so a missing dependency shows as `Init:Error` naming the container, not as a Running pod doing nothing. The images are ones the base already pins by digest (curl, CloudNativePG's Postgres, Valkey, SeaweedFS for the bucket Job), so there is no new image and no new dependency.
- **Bindings, not just queues.** A message published to an exchange with no bound queue is confirmed and dropped. So `tigerbeetle-cdc` waits until `bridge.ledger` is bound to its exchange, and `amqp-fanout` waits for that binding and for the projection's transfers queue and all `PROJECTION_ENTRIES_QUEUE_COUNT` entries queues to be bound. It reads those names from `projection-worker-config`, because `projection-worker` declares those queues on its first connection and `amqp-fanout` must not republish before then. The workloads that wait for RabbitMQ gain NetworkPolicy egress to the management port, 15672. RabbitMQ already admitted them there.
- **The mesh.** Linkerd is installed with its defaults, so `linkerd-init` redirects a pod's traffic before any other init container runs, and the classic proxy starts only after all of them. The waits would have nowhere to send their traffic, and with no mesh identity the datastores' AuthorizationPolicies would refuse them anyway. Meshed pods with waits therefore run the proxy as a Kubernetes native sidecar, an init container that starts first (`config.alpha.linkerd.io/` and `config.beta.linkerd.io/proxy-enable-native-sidecar: "true"`; both spellings because the installed Linkerd follows whatever CLI is on the operator's PATH). This needs Kubernetes 1.29 or later; kind runs 1.31. The exception is `wait-for-tigerbeetle`, which runs as the proxy's UID 2102 so that its traffic bypasses the proxy. Through a proxy, a TCP connect succeeds whether or not a replica is behind it, and TigerBeetle is unmeshed anyway (ADR-0029). The live runs must still confirm that `linkerd-proxy` is listed before the waits. If it is not, every wait times out, which is visible.
- **Readiness reflects consumption.** `amqp-fanout` and `projection-worker` each add a `listenerContainers` health indicator to their readiness group. It reports `DOWN` unless every container in the `RabbitListenerEndpointRegistry` is running. A container whose consumers are still retrying, such as a standby's refused exclusive consume or a broker that is restarting, counts as running. A container that spring-rabbit has stopped after a fatal consumer error does not. It is wired to readiness only, never liveness, for the reason ADR-0032 gives for the halt. `missing-queues-fatal: false` (the GKE fix) still stands.
- **Setup Jobs retry inside the pod.** A failed Job is never run again. `rabbitmq-topology` now waits for the broker before its first declaration. `object-store-buckets` waits for the store and retries each bucket on any transport error until the deadline. Both get `activeDeadlineSeconds: 3600` (it was 600 on the bucket Job), longer than a full wait with room for a retry.
- `StartupDependencyWaitValidationTest` derives each workload's dependencies from the Secrets and ConfigMaps its containers reference and fails until each has its wait. It also checks that the waits are pinned and hardened, that meshed pods have the native sidecar, that the bindings and the NetworkPolicy ports are in place, and, when `kubectl` is present, that all four overlays keep every wait and that every key a wait reads resolves. Not gated: `eod-job` publishes its sign-off before checking that `statement-worker`'s queue is bound (it publishes at the daily close, not at startup), and `projection-worker`'s optional trace-correlation Valkey is not waited for either.

---

## Questions still open

Resolved questions are recorded in the decisions above. These remain open or deferred:

| Topic | State |
|---|---|
| Legal form and regulatory entity type | Open. It determines whether ISR withholding is the platform's obligation or a custodian's. |
| NAV frequency, and who holds assets | Daily NAV assumed; custody modelled per ADR-0019. |
| Participant fund choice vs plan-assigned funds | Open; participant choice would trigger a suitability apparatus. |
| Cost basis: average cost vs FIFO | Lots are stored regardless; decide when withdrawals are built. |
| NAV error materiality threshold and compensation policy | Open; agree before the first NAV, not during the first error. |
| Declared-rate products (an implicit guarantee) | Not in v1; would need a priced spread, a reserve and daily monitoring. |
| Bank statement integration (ADR-0010) | Open; v1 posts cash receipts manually. |
| Payroll-vendor connectors and direct API push | Deferred past v1. |
| Provider-account retirement mechanism (ADR-0019) | Needed before the first real segregated integration. |
| Per-datastore TLS; multi-node WireGuard verification (ADR-0029/0030) | Follow-ups before production. The GKE load-test environment (ADR-0036) checks Dataplane V2's WireGuard, not Calico's. |
| Interest/fee grandfathering, fraud timeouts, data residency, who operates the platform | Not designed; placeholders only. |

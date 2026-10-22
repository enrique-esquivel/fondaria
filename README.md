# Fondaria — employee savings and retirement fund administration (Mexico)

> **Not production software.** This is a design and engineering demo. Every
> organization, participant, RFC, CURP, amount and credential in this
> repository is fictitious or a local-development default. Nothing here has
> been audited, certified or operated with real money.
>
> **Not affiliated with any company.** "Fondaria" is the name of this demo
> only; the project has no connection to any business, product or trademark
> using that name.

A platform for administering employee savings and retirement funds for Mexican
organizations (*caja de ahorro*, and an employer-sponsored retirement fund with
vesting). Employers remit contributions, the pool is invested externally, and
gains and losses are spread across the employees ("participants"). The core
model: **participants hold units, not pesos.** Units are a quantity kept in the
ledger and change only on contribution, withdrawal or fund switch; the NAV per
unit is one price per fund per valuation date, append-only and versioned; a
participant's value is `units held × NAV`, computed on read and never stored.
When the pool gains or loses, one number (the NAV) changes and every
participant's value moves with it — there is no per-participant earnings
calculation, and a contribution never moves the NAV.

## Architecture

Java 21, Spring Boot 3.5, Maven; blocking code on virtual threads.

| Component | Role |
|---|---|
| **TigerBeetle** | The ledger and the only balance store. Cash (MXN cents) and units (one ledger per fund, scale 1e-8) are structurally separate ledgers. |
| `libs/ledger-client`, `libs/ledger-tigerbeetle` | The `LedgerPort` contract and its single TigerBeetle implementation; deterministic transfer ids from idempotency keys. |
| `services/contribution-api` | Organizations, plans, participants; employer contribution files and cash receipts. |
| `services/valuation-api` | Ingests external valuations and strikes the NAV per unit. |
| `services/allotment-job` | Turns a staged contribution batch into units at a struck NAV, atomically. |
| `services/balance-api` | Read side: holdings from the ledger valued at the cached NAV, never from PostgreSQL. |
| TigerBeetle CDC → **RabbitMQ** → `services/amqp-fanout` | Every committed ledger movement is published once and fanned out to transfer and entry streams. |
| `services/projection-worker` | Idempotent CDC consumer maintaining the append-only `projection` schema (history, statements). |
| `services/eod-job` | Daily close: internal and custodian reconciliation, hashed daily snapshot. |
| `services/statement-worker` | Month-end statement fan-out, one content-hashed statement per participant. |
| `libs/rules-engine` | Effective-dated, versioned product rules (vesting, caps, UMA/ISR); every evaluation stores its inputs. |
| **PostgreSQL 17** (Flyway) | Master data, effective-dated reference data, workflow state, CDC projection — never a balance. |
| **Valkey** | NAV cache. |
| **SeaweedFS** (S3 API) | Valuation files, snapshots, statements. |
| **Keycloak** | Identity (OIDC). |
| Kubernetes (kind locally) | Kustomize overlays, Skaffold, Linkerd mTLS, Envoy Gateway, CloudNativePG, RabbitMQ operator, OpenTelemetry / Prometheus / Loki / Tempo / Grafana. |

Documentation lives in `docs/`: [`DOMAIN.md`](docs/DOMAIN.md) (what the
platform does and why units, not pesos), [`DESIGN-DECISIONS.md`](docs/DESIGN-DECISIONS.md)
(the architecture decisions, cited in code as `ADR-00NN`) and
[`RUNBOOKS.md`](docs/RUNBOOKS.md) (operating it). The ledger accounts are in
[`docs/chart-of-accounts.md`](docs/chart-of-accounts.md) and the unit-rounding
rule in [`docs/rounding-policy.md`](docs/rounding-policy.md); future ideas are in [`docs/BACKLOG.md`](docs/BACKLOG.md). [`docs/ENGINEERING-RULES.md`](docs/ENGINEERING-RULES.md)
holds the engineering rules the code comments cite (`docs/ENGINEERING-RULES.md §4` and so on).

## Running it locally

Prerequisites: a Temurin 21 JDK (`JAVA_HOME` must point at it; Homebrew
`openjdk@21` crashes Spotless), Maven 3.9+, Docker with at least 12 GB of memory
(16 GB recommended) and ~30 GB free disk, plus `kind`, `kubectl`, the `linkerd`
CLI and `skaffold` for the cluster.

```bash
make help            # list every target
make verify          # build + unit tests + formatting (excludes integration-tagged tests)
make verify-all      # also the Testcontainers integration suite

make up              # core infrastructure in Docker Compose (make up-all adds Keycloak + observability)
make down

make k8s-up          # local kind cluster and operators
make skaffold-run    # build and deploy every service via Skaffold (the only supported deploy path)
make demo-seed       # 3 funds, 12 employers, ~300,000 fictitious participants
make demo-day        # one simulated business day: contributions, then a NAV move
make demo-onboard    # onboard a 200,000-employee employer live
make demo-reset      # tear down and reseed from scratch (~4 min)
```

A fresh clone needs no secret material: `make k8s-up`, `make skaffold-run` and
`make verify` first run `make local-secrets`, which generates the local
cluster's private keys (see Security below) and does nothing once they exist.

Stop the Compose stack and run `make test-fixtures-clean` before `make k8s-up`:
the reused Testcontainers fixtures and the cluster compete for Docker memory.
The full operator guide, including failure modes, is `docs/demo/RUNBOOK.md`;
the Compose stack is described in `docs/RUNBOOKS.md#local-setup`.

## Security

- **Edge.** Envoy Gateway is the only entry point (`infrastructure/k8s/base/gateway/`,
  ADR-0028). It terminates TLS (1.3 only), validates every bearer token's signature,
  issuer and audience against Keycloak's JWKS with a `SecurityPolicy` before a request
  reaches an API, and applies per-route rate limits (`BackendTrafficPolicy`, per subject and
  per organization; contribution uploads have their own, tighter budget). Role and
  organization checks stay in the services: they are business rules, not edge rules.
- **East-west.** Linkerd gives every application service mutual TLS and a workload identity
  (ADR-0029). The control plane is installed with `--default-inbound-policy=deny`, so a
  meshed pod accepts nothing until a `Server`/`AuthorizationPolicy` names its caller
  (`infrastructure/k8s/base/linkerd-policy/`).
- **Datastores.** PostgreSQL, RabbitMQ, Valkey and the object store were meshed later
  (ADR-0029's storage amendment) and each authorizes only the service accounts that use it.
  TigerBeetle and its CDC reader stay deliberately unmeshed. Every pod sits behind a
  default-deny `NetworkPolicy` with explicit per-pair allows
  (`infrastructure/k8s/base/networkpolicies/`). Calico WireGuard encrypts all node-to-node
  pod traffic, including TigerBeetle's (ADR-0030).
- **Secrets.** None are committed. The local cluster's private keys (the gateway's
  self-signed TLS pair, Linkerd's trust anchor and identity issuer) are generated by
  `infrastructure/k8s/bootstrap/local-secrets.sh` into the gitignored
  `infrastructure/k8s/overlays/local/generated/`. Production secrets come from
  `ExternalSecret`s backed by a `ClusterSecretStore`. The base/prod Keycloak realm has no
  user passwords and no client-secret literals: the client secrets are resolved at import
  from a Secret, and the demo users exist only in the local overlay. Production fails
  closed: a missing Secret stops the pod from starting instead of falling back to a default.
  The local-only defaults (Compose `${VAR:-value}`, the local overlay's generated Secrets,
  test fixtures) are fictitious development values. `PublicReleaseSecretsGuardTest` fails
  the build if a private key is tracked or the rendered prod overlay contains a Keycloak
  credential.

## Scaling

Replica counts from `infrastructure/k8s/base` (the prod overlay raises some floors; the local
overlay runs one replica of nearly everything).

| Workload | How it scales |
|---|---|
| `balance-api` | HPA on CPU (70%), 2–8 replicas (prod floor 3) |
| `contribution-api` | HPA on CPU (70%), 2–6 replicas (prod floor 3) |
| `valuation-api` | HPA on CPU (70%), 2–4 replicas (prod floor 3) |
| `allotment-job` (1), `projection-worker` (2), `amqp-fanout` (2), `statement-worker` (2; 4 in prod) | Fixed replicas, scaled by hand |
| TigerBeetle | A 6-replica cluster (StatefulSet). It scales out by adding a cluster per fund, never by splitting a fund (ADR-0005); `LedgerPort` resolves the cluster by fund |
| PostgreSQL | CloudNativePG, 3 instances (one primary, synchronous replicas), scaled vertically |
| RabbitMQ | RabbitMQ cluster operator, 3 nodes |

The three HPA maxima are sized together against TigerBeetle's client limit
(ADR-0033); raising one means lowering another or adding a ledger cluster.

## Load-test numbers (honest version)

Measured on **one kind node with 10 CPUs and 16 GB**, local resource limits
(PostgreSQL capped at 2 CPUs, 64 MB `shared_buffers`). These are not
production capacity figures. 

- **Allotment** (contribution rows turned into units; ~2 ledger transfers per
  row): ~3,900 rows/s at 3 replicas, near-linear from 1 to 3; past
  that the shared PostgreSQL audit-table write path is the wall. A later run
  measured ~6,260 rows/s at 4 replicas — about 2.5× faster than
  the earlier run on the same code, attributed to the environment and not
  investigated.
- **End to end** is limited by the CDC → `amqp-fanout` → projection pipeline,
  not by allotment. Before batching the projection sustained ~190 rows/s
  equivalent; after batched consumption (ADR-0034) the projection keeps up and
  the single `amqp-fanout` replica (~530 msg/s drain) is the limit — 100,000
  rows at 4 allotment replicas finished end to end in ~5.6 minutes.
- TigerBeetle stayed at 0.1–0.2 CPU throughout.

## Licence

MIT. See [`LICENSE`](LICENSE).

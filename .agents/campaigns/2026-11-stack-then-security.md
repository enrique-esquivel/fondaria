# Campaign: stack first, then security (2026-11)

Order: a trustworthy baseline → the stack → the security review of the updated stack. Reviewing first would audit code and dependencies that are about to be replaced; upgrading on a red baseline would make regressions impossible to attribute.

Versions checked on 2026-11-24 (endoflife.date, Maven Central, GitHub releases). Re-check when the issue starts.

| Component | In use | State | Latest |
|---|---|---|---|
| Spring Boot | 3.5.16 | **EOL 2026-06-30**, last 3.5 | 4.2.0 (4.1.3) |
| Keycloak | 26.1.0 | **EOL** (26.7 and older are) | 26.8.2 |
| RabbitMQ | 4.0.9 | **EOL 2025-04-30** | 4.4.1 (4.3.8) |
| Kubernetes (kind node) | 1.31.9 | **EOL** (1.34 and older are) | 1.37.2 |
| Envoy Gateway | 1.3.0 | old; confirm | look up |
| PostgreSQL (CNPG image) | 17.2 | supported, 10 minors behind | 17.12 / 18.7 / 19.0 |
| Valkey | 8.0.2 (compose) | supported to 2027-09, 11 patches behind | 8.0.13 / 9.2.0 |
| TigerBeetle server + client | 0.17.4 | behind | 0.17.9 |
| SeaweedFS | 4.47 | behind | 4.55 |
| Java | 21 | supported; 25 is the newer LTS | 25.0.5 |
| Flyway | 11.2.0 (plugin 11.7.2) | two versions in one build | 13.13.0 |
| Testcontainers / jqwik | Boot-managed / 1.9.2 | behind | 2.0.5 / 1.10.2 |

## B1 — Integration suite: green three times in a row
```meta
phase: baseline
depends: none
```
The scheduled suite failed on 2026-11-13, 11-17 and 11-24 (green on 11-20), each time in different tests: `BridgeIntegrationTest.aBrokerThatRefusesPublishesHaltsTheBridgeWithBoundedChannelsAndNoDeadLetters`, `InternalReconciliationEndToEndTest.anInjectedProjectionRowIsDetectedQuantifiedAndBlocks`, `KubeconformIntegrationTest`, `OperationalHealthMetricNamesAreRealIntegrationTest`.

Find each cause from the CI run's reports and container logs. Fix the test's dependency on timing, network or order, or the product bug behind it. No retries, no longer waits without a stated reason.

Done: three consecutive `make verify-all` runs with different `TEST_SEED`s pass, and the seeds are in the PR.

## B2 — Dependency scan produces a result
```meta
phase: baseline
depends: none
```
The weekly scan fails in about a minute: the repository has no `NVD_APIKEY` secret, so it receives an empty key. The scan has no baseline. Make a missing key fail with a clear message (or run without a key, slower, if that works within the job limit), and state what the owner must set. Do not put scan results in the PR: findings about our own exposure go through `harness finding`.

Done: the workflow's outcome says "no key" or "scanned", never a stack trace.

## S1 — Decide the target versions
```meta
phase: stack
depends: B1
```
`docs/ENGINEERING-RULES.md` §4 and §5 pin Java 21, Spring Boot 3.5, PostgreSQL 17, Valkey 8.0, Flyway 11.x and SeaweedFS 4.47. Moving any of them is the owner's decision.

`escalate` one question with options, cost, risk and a recommendation per row: Spring Boot 4.1 or 4.2; Java 21 or 25; PostgreSQL 17.x only or 18; Valkey 8.x or 9.x; RabbitMQ 4.3 or 4.4; Flyway as managed by the chosen Boot or newer. Weigh support end dates, and prefer a release that has had patch releases.

Done: the owner's answer is recorded in `docs/DESIGN-DECISIONS.md`, and §4/§5 of the rules say the new pins. Nothing else changes in that PR.

## S2 — Build tools and leaf libraries
```meta
phase: stack
depends: B1
```
Maven plugins (compiler, surefire, dependency, enforcer, versions, jacoco, jib, spotless), AWS SDK BOM, PostgreSQL JDBC, OpenTelemetry agent, jqwik. Stable releases only (the compiler plugin's newest is a 4.0 beta: stay on 3.x). A google-java-format change that reformats the tree goes in its own commit with nothing else in it.

Done: `make verify-all` green; `banDuplicateClasses` still passes.

## S3 — Patch releases that need no decision
```meta
phase: stack
depends: B1
```
PostgreSQL 17.2 → 17.12 (compose, CNPG image, CI, Testcontainers), Valkey 8.0.2 → 8.0.13, the Java 21 base image digest. Pin exact tags, with digests where digests are used today. The unpinned `postgres:17-alpine` in compose and CI breaks the pinning rule: fix it here.

Done: one version of each engine across compose, Kubernetes, CI and fixtures, and a test or grep that says so.

## S4 — Java and runtime image
```meta
phase: stack
depends: S1,S2
```
Move to the Java version chosen in S1: `maven.compiler.release`, Temurin in CI and `jdk-check`, the distroless base image, the OpenTelemetry agent. If S1 keeps 21, close this with that note.

## S5 — Spring Boot 4
```meta
phase: stack
depends: S1,S4
dependencies: allowed
```
Largest step. Read the 4.0 and later migration guides end to end. Check at least: Jackson 3 (package, and number handling: cash, units and NAV must still serialize as integers), JUnit and Testcontainers majors, Spring AMQP container defaults (prefetch, per-consumer QoS, confirms, the settings behind ADR-0042), Spring Security defaults, Actuator and metric names used by dashboards and alerts, JDBC and transaction defaults, virtual threads, structured logging field names. Temporary compatibility modules must be gone by the end.

Done: `make verify-all` green; a test proves the JSON of every money field is byte-identical to before.

## S6 — Flyway
```meta
phase: stack
depends: S1,S5
```
One Flyway version for the library and the plugin. Prove that validating an existing database migrated by 11.2.0 passes with no checksum drift, and that a fresh migrate works.

## S7 — PostgreSQL major
```meta
phase: stack
depends: S1,S3
```
Only if S1 chose a new major. Include the CNPG upgrade procedure, collation and index implications (the close walks `COLLATE "C"` indexes), and a runbook. Otherwise close with that note.

## S8 — RabbitMQ
```meta
phase: stack
depends: S1,S3
```
Follow the documented upgrade path and feature-flag requirements between 4.0 and the target. Check quorum queues and delivery limits, classic queue behaviour, the stream/consistent-hash features in use, `amqp` compatibility with TigerBeetle's CDC job, and the cluster operator version. Record whether a cluster can be upgraded in place or must be drained.

## S9 — Valkey major
```meta
phase: stack
depends: S1,S3
```
Only if S1 chose a new major. Lua scripts must stay integer-only, with the same results. Otherwise close with that note.

## S10 — Keycloak
```meta
phase: stack
depends: B1
```
26.1.0 → latest 26.x. Realm import, token claims and lifetimes, audience and issuer checks, removed options. Prove login and every role the services check.

## S11 — TigerBeetle
```meta
phase: stack
depends: S1,S2
```
Client and server move together, 0.17.4 → 0.17.9, through `libs/ledger-tigerbeetle` only. Check the client/server compatibility window, the data-file upgrade, CDC job changes, and every status code the adapter maps. Update the image digest and the data-file runbook. This issue changes nothing else.

Done: crash/replay and 100,000-transfer rebuild tests green, plus an upgrade test that opens a data file written by 0.17.4.

## S12 — Kubernetes, mesh and gateway
```meta
phase: stack
depends: B1
```
kind node image, CloudNativePG and RabbitMQ operators, Envoy Gateway, rate-limit image, Linkerd, Calico, kube-state-metrics. Look up the supported combination first and put the matrix in the PR. Manifests must validate (`make verify-k8s`). Do not create a cluster: the owner runs it and records the result.

## S13 — Observability and object store
```meta
phase: stack
depends: B1
```
Prometheus, Grafana, Loki, Tempo (2.9 → 3.x is a major), OpenTelemetry collector, SeaweedFS 4.47 → 4.55, curl. Alerts and dashboards must still load, and every referenced metric must exist.

## S14 — Stack closed
```meta
phase: stack
depends: B2,S2,S3,S4,S5,S6,S7,S8,S9,S10,S11,S12,S13
```
Re-check every row of the campaign table; no component in use is EOL; docs, rules and manifests name the same versions; three `make verify-all` runs green. `escalate` the request for a load run (the owner runs it) and record its per-step timings against the last one.

## SEC1 — Scope and threat model
```meta
phase: security
depends: S14
```
`docs/security/scope.md`: assets (participants' money, personal data, NAV), actors (participant, employer, operator, another tenant, a compromised workload, a malicious file), entry points, trust boundaries, and the areas below with what "covered" means for each. No findings in the document.

## SEC2 — Tenant isolation and authorization
```meta
phase: security
depends: SEC1
labels: independent:claude
```
Every endpoint and query of every service: authentication, role and scope check, tenant_id first in every query, message and key; identifiers that can be guessed; staff and admin paths; token validation. Trace each endpoint to its check; an endpoint with none is a finding.

## SEC3 — Money paths
```meta
phase: security
depends: SEC1
labels: independent:gemini
```
Idempotency-key to transfer-id derivation (collisions, cross-tenant reuse), linked transfers, pending lifecycle, overflow and rounding, concurrent requests, replayed and reordered messages, allotment and NAV version links, receipts asserted by operators, reconciliation bypass.

## SEC4 — Untrusted input
```meta
phase: security
depends: SEC1
labels: independent:claude
```
Uploaded contribution and NAV files (size, encoding, formulas, parsers), SQL built from input, path and object-key construction, presigned URLs, deserialization, request limits, error bodies (RFC 9457, no leaks).

## SEC5 — Personal data and logs
```meta
phase: security
depends: SEC1
labels: independent:gemini
```
`docs/ENGINEERING-RULES.md` §7: RFC, CURP, names, salary, balances and tokens in logs, traces, metrics, messages, DLQs, object-store keys and test data; retention; statements.

## SEC6 — Platform and secrets
```meta
phase: security
depends: SEC1
labels: independent:claude
```
Kubernetes base and overlays (privileges, network policies, service accounts, seccomp), mesh authorization, gateway routes exposed, datastore TLS and credentials, secret generation scripts, production fail-closed on missing secrets, GKE environment.

## SEC7 — Supply chain and repository
```meta
phase: security
depends: SEC1
labels: independent:gemini
```
Workflow permissions and action pinning (tags today, not commit hashes), what a fork PR can trigger, image provenance and digests, dependency scan results, secrets in git history, `main` protection, and the agent lanes: the adapter (`.agents/`), and what an unattended session on the owner's machine can reach.

## SEC8 — Close-out
```meta
phase: security
depends: SEC2,SEC3,SEC4,SEC5,SEC6,SEC7
```
`docs/security/coverage.md`: what was examined, by which method, and what was not. Every finding is judged (`harness findings`). `escalate` so the owner decides the fix issues.

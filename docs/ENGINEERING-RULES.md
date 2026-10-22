# Engineering rules

The standing technical rules for this repository. Code comments, migrations and tests cite them as `docs/ENGINEERING-RULES.md §N`, so the section numbers are stable (§3, §8 and §9 do not exist). Why each rule exists is in [DESIGN-DECISIONS.md](DESIGN-DECISIONS.md); what the platform does is in [DOMAIN.md](DOMAIN.md).

---

## 1. Principle

**Correctness of participants' money outranks everything: speed, elegance and personal preference.** Customers are organizations; the beneficiaries are their employees (participants). The platform receives valuations from outside and translates them into participant positions; it does not manage the portfolio.

## 2. The one model to understand

Participants hold **units**, not pesos.

```
participant balance = units held × NAV per unit
```

- **Units** are a quantity, stored in the ledger (TigerBeetle). They change only on contribution, withdrawal or fund switch.
- **NAV per unit** is a price — one number per fund per valuation date, held in the valuation store and cached in memory.
- **Value** is a multiplication. It is never stored as a balance and never summed from PostgreSQL.

When the pool gains or loses, **one number changes** (the NAV) and every participant's value moves correctly. There is no per-participant earnings calculation. If you find yourself writing one, stop.

---

## 4. Hard rules

**Numbers**
- Cash is `long` in **cents**. Units are `long` at **scale 1e-8**. NAV is `long` at **scale 1e-8**.
- NEVER `double`, `float` or `BigDecimal` for cash, units, NAV, rates or returns — in storage, transport, computation or JSON. `BigDecimal` is allowed only at the display boundary.
- No `INCRBYFLOAT` in Redis. Integer operations only, inside a Lua script.
- **Every value, gain, return and market-movement field is signed.** Values go down. Never clamp a loss at zero, never use an unsigned type, never add `CHECK (>= 0)` to a gain.

**The ledger**
- TigerBeetle holds two kinds of quantity, structurally separated by the `ledger` field: **cash** (ledger 484, MXN cents) and **units** (one ledger id per fund). Cross-ledger transfers are impossible by design — do not work around this.
- `LedgerPort` resolves a cluster by FUND. A fund is never split across
  clusters. A transfer whose legs resolve to different clusters must be
  rejected.
- Every table, message key and query is scoped by tenant_id first.
- Balances are read via `LedgerPort.lookupAccount`. Never compute a participant balance by summing rows in PostgreSQL.
- **There is exactly one `LedgerPort` implementation: TigerBeetle** (`ADR-0008`). Do not write a second ledger implementation, in PostgreSQL or anything else.
- **PostgreSQL has four schemas with different rules** (`ADR-0011`). `projection` is CDC-fed — history and statements, never written by a service, never the authority for a balance, never rule enforcement. `master` (organization, participant, plan), `reference` (effective-dated data and rule-evaluation evidence, `UPDATE`/`DELETE` revoked) and `workflow` (service-owned status lifecycles) **are** written directly by services. A balance still comes only from `LedgerPort`.
- The ledger stores money and links, never descriptive data. A name, an `RFC` or a bank reference lives in PostgreSQL; TigerBeetle's user data carries only a SHA-derived reference tying the two together.
- **An adapter is a thin translator, never a rule engine.** It maps the engine's own results (`ExceedsCredits` → `INSUFFICIENT_UNITS`); it never computes whether a balance suffices. Balance floors, id idempotency, linked-chain atomicity and the pending lifecycle are enforced *inside* the engine, atomic with the write — checking them a layer above reintroduces a read-then-write race. Product policy (vesting, caps, ISR, UMA) is the opposite: it belongs above the port, in `libs/rules-engine`.
- Never `UPDATE` or `DELETE` a row in `transfer`, `fund_nav`, `participant_units`, or any audit table. Corrections are new rows.
- Never use a TigerBeetle type in service code. Always go through `libs/ledger-client`'s `LedgerPort`.
- Transfer ids are derived deterministically from an idempotency key. Never generate a random transfer id in a request path.

**Valuation**
- NAV is **append-only and versioned**. A wrong NAV is never deleted; a corrected NAV is recorded alongside it.
- Every unit allotment records **which NAV version it used**. Without that link, correcting a NAV error is impossible.
- A contribution never moves the NAV. Pool value and units in issue rise proportionally; the price is unchanged.

**Rules and reference data**
- UMA, INPC, withholding rates, contribution caps, product configuration and plan rules are **effective-dated versioned data** — never constants, never environment config.
- Every rule evaluation stores its inputs, the rule version and the outcome. Calculations must be reproducible years later from stored data, never by rerunning current code.

**Data flow**
- A request handler writes exactly ONE system of record **per fact**. If you're writing to the ledger *and* publishing an event, stop — CDC already publishes it. Recording two genuinely different facts is allowed ("an operator asserted this wire exists" is not "money moved"): write the non-ledger fact first, make both idempotent on derived keys, and a crash between them must leave a row whose status says the movement is unconfirmed — never money moved with nothing recording why (`ADR-0011`).
- Every consumer is idempotent, keyed on `transfer_id`. Assume every message arrives more than once.
- Never skip a message you can't process. Send it to the DLQ and halt. A gap in a financial projection is worse than downtime.

**Code**
- Java 21, Spring Boot 3.5, Maven. Blocking code with virtual threads (`spring.threads.virtual.enabled=true`).
- Prohibited without an ADR: WebFlux, Mono, Flux, Reactor, R2DBC, Hibernate/JPA, Lombok, any ORM, any cloud-provider SDK (the S3 API client is allowed for object storage only), any new framework.
- No `synchronized` around I/O — it pins virtual threads. Use `ReentrantLock`.
- Constructor injection only. No field `@Autowired`. No `null` returns from public methods.
- Every external call has an explicit timeout.
- No new dependency unless the task names it.

**Configuration**
- No hardcoded credentials, hosts, ports or URLs. Environment only, with documented local-dev defaults.
- Any new environment variable goes into `infrastructure/compose/.env.example` **and** the Kustomize base in the same commit.

**Local Infrastructure & Containers**
- Every image in `docker-compose.yml` MUST be strictly pinned to an exact release version or dated release tag — never `:latest` or wildcard tags.
- All Docker network names, service names, container names, database names, volume paths and configuration identifiers must be in **English** (e.g. network `funds`).
- TigerBeetle containers require `/tigerbeetle` binary path in entrypoint scripts, and `security_opt: [ "seccomp=unconfined" ]` (or `--development`) to run without Direct IO / `io_uring` permission errors.
- Entrypoint scripts must be idempotent (e.g. TigerBeetle format runs ONLY when data file does not exist).
- Credential defaults (${VAR:-value}) are permitted ONLY in the local
  compose file. Production configuration must fail to start when a
  required secret is absent — never fall back to a default.

**Testing**
- Every task producing code produces tests in the same commit.
- Integration tests use Testcontainers, never a hand-run local database.
- **Fixtures are reused across local runs, so a test must never assume an
  empty ledger or database.** Generate fresh ids (`UUID.randomUUID()`), and
  assert only over ids the test itself created — never a total over a whole
  `ledger` id, table or topic. TigerBeetle in particular cannot be reset.
  Aggregate over everything and the test passes or fails on what earlier
  runs left behind, which is the worst failure mode there is: intermittent,
  and about money.
- **Test suites must include falling and volatile NAV series.** A suite that only exercises rising prices will pass on a broken implementation.
- Never write a script, harness, or helper tool to satisfy an acceptance
  criterion unless the task names the file. If a criterion seems to need
  one, say BLOCKED.
- Never use `docker exec -it` or any interactive flag. There is no
  terminal attached to your session.

**Migrations**
- Flyway only. Versioned files, never edited after merge. Expand/contract — a migration must leave the previous application version able to run.

## 4a. Language

All code is in **English**. Identifiers, types, fields, methods, packages,
enum constants, database tables and columns, JSON field names, API paths,
log messages, comments, commit messages and test names.

Use `minorUnits`, not `centavos`. `contribution`, not `aportacion`.
`withdrawal`, not `retiro`. `participant`, not `participante`.

Two exceptions:

1. **Mexican regulatory and legal identifiers keep their official names**,
   because translating them breaks the link to the law and to the source
   documents. Keep as-is: `UMA`, `RFC`, `CURP`, `ISR`, `INPC`, `UDI`,
   `LISR`, `CONSAR`, `AFORE`, `PPR`, `SAT`, `IMSS`, and product names
   like `FONDO_DE_AHORRO` and `CAJA_DE_AHORRO`. Write them as they appear
   in the regulation. Do not invent English equivalents such as
   `SavingsFundType` for `fondo de ahorro`.

2. **User-facing text is Spanish, and lives only in i18n resource
   bundles** — never hardcoded in a class, never in an identifier.
   The key is English (`statement.market_movement`); the value is Spanish.

## 5. Technology — pinned, do not substitute

| Purpose | Technology |
|---|---|
| Ledger | TigerBeetle 0.17.x — via `libs/ledger-client` only |
| Broker / CDC | **RabbitMQ 4.x** — the only broker; native `amqp` job and consumers read from (ADR-0022) |
| Warm store | PostgreSQL 17, JDBC + `JdbcClient` |
| Migrations | Flyway 11.x |
| Cache | Valkey 8.0, Lettuce, Lua for atomic ops |
| Object store | SeaweedFS 4.47 (S3 API only; ADR-0031) |
| Identity | Keycloak 26.x |
| Runtime | Java 21 (Temurin), distroless container, non-root |
| Tests | JUnit 5, AssertJ, Testcontainers, jqwik |

## 6. Layout

```
docs/                 DOMAIN.md, DESIGN-DECISIONS.md, RUNBOOKS.md, ENGINEERING-RULES.md, BACKLOG.md
contracts/            openapi/, events/, files/
libs/ledger-client/   LedgerPort contract, domain types, id derivation (no vendor dep)
libs/ledger-tigerbeetle/ the one LedgerPort implementation (ADR-0008)
libs/platform-common/ Money, Units, Nav, error model, tracing
libs/rules-engine/    effective-dated rule evaluation, dry-run, diff
services/<name>/      one service per directory
infrastructure/       compose/, k8s/
```

Never create a directory outside this structure.

## 7. Error and log discipline

- APIs return RFC 9457 problem details. Never leak an exception message, stack trace, SQL fragment or hostname.
- Logs are structured JSON with `timestamp`, `level`, `service`, `traceId`, `message`.
- **Never log:** RFC, CURP, names, email, salary, balances, tokens, idempotency keys, document contents.
- Log every cash or unit movement with `transfer_id` and `correlation_id` — traceable without logging its contents.

---

## 9. When unsure

Stop and ask rather than guess. An hour of clarification is cheaper than a fund that does not reconcile.

---

## 10. Working agreement

- Every change branches from `main` and its pull request targets `main`. Never stack a branch on another still-open branch: if change N+1 depends on N's unmerged code, wait for N to merge, then branch.
- No machine-specific paths (`/Users/...`, `/opt/homebrew`, absolute toolchain paths) in the build or scripts.
- Do not add a dependency, a module or a top-level directory without agreement.
- Run `make verify` before claiming completion; `make verify-all` also runs the integration suite.

# AGENTS.md — Pinned context for the implementing agent

> Repository root. Loaded on every task. Keep under ~200 lines — a long rules file gets partially read.

---

## 1. What this is

A platform administering **employee savings and retirement funds in Mexico**. Customers are organizations (20–10,000 employees); the beneficiaries are their employees ("participants").

Money arrives from employers, is invested externally, and gains and losses are spread across participants. **We do not manage the portfolio.** We receive valuations from outside and translate them into participant positions.

Products include *fondo de ahorro*, *caja de ahorro*, private retirement plans (PPR) and high-yield savings. They differ by configuration, not by code path. **v1 ships two vehicles: caja de ahorro and an employer-sponsored retirement fund with vesting** — see `ADR-0004` (vesting mechanism) and `ADR-0012` (vehicle scope).

**Correctness of participants' money outranks everything: speed, elegance, and your own preferences.**

Before any Phase 2 task, read:
- `docs/DOMAIN.md` §4 (ledger model)
- `docs/DOMAIN.md` §1–2 (NAV, why the ledger is the balance store)
- `docs/DESIGN-DECISIONS.md (ADR-0004)`

## 2. The one model you must understand

Participants hold **units**, not pesos.

```
participant balance = units held × NAV per unit
```

- **Units** are a quantity, stored in the ledger (TigerBeetle). They change only on contribution, withdrawal or fund switch.
- **NAV per unit** is a price — one number per fund per valuation date, held in the valuation store and cached in memory.
- **Value** is a multiplication. It is never stored as a balance and never summed from PostgreSQL.

When the pool gains or loses, **one number changes** (the NAV) and every participant's value moves correctly. There is no per-participant earnings calculation. If you find yourself writing one, stop.

## 3. How you work

- **The task is the first unchecked row of `docs/ai-tasks/BACKLOG.md` §
  "Task sequence"**, unless the user names a different one. Read that table
  first, then the phase document it points to. Do not ask which task is next
  and do not infer it from git history.
- **Then read `docs/ai-tasks/KEY-TYPES.md`** — the index of types that already
  exist. Reuse what is there; never rebuild a primitive, an id derivation or a
  rounding rule that is already merged.
- One task per session. Nothing else.
- Only create or edit files the task names.
- You do not make design decisions. If a task is unclear or looks wrong, output `BLOCKED: <question>` and stop. Do not guess.
- When you believe you're done, run `make verify` and paste the **full** output. Never claim success without it.
- Do not change a test to make it pass. If a test is provably wrong, say `BLOCKED` and explain.

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
docs/architecture/    design docs, adr/
docs/ai-tasks/        BACKLOG.md, TASK-LOG.md, KEY-TYPES.md, phase-XX-*.md
contracts/            openapi/, events/
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

## 8. Definition of done

- [ ] Every acceptance criterion demonstrably met, each with a test
- [ ] `make verify` green, full output pasted
- [ ] Adversarial review gate — see below
- [ ] No dependency outside the pinned list (or an ADR exists)
- [ ] New env vars in both compose and Kustomize
- [ ] `BACKLOG.md` updated — tick the row in § "Task sequence" **and** update
      § "Where we are" (keep it to a couple of sentences per task)
- [ ] `TASK-LOG.md` updated — append the task's design decisions, traps
      avoided and alternatives discarded as the next numbered step,
      including the assumptions and findings from any adversarial review
      run this session (its own task's, or a deferred one it closed out —
      see below)
- [ ] A decision with consequences beyond this task becomes a new ADR
      (`docs/ADR-00XX-*.md`), not just a paragraph in `TASK-LOG.md`
- [ ] `KEY-TYPES.md` updated if the task added or removed a public type in `libs/`
- [ ] One commit, message `phase-XX/task-YY: <what>`
- [ ] Session closes with a short, plain-language functional description of
      the change — what's new, from the user's perspective — distinct from
      the technical `TASK-LOG.md` entry

**Adversarial review gate — risk-based, not per-task.**

Review a task **immediately** (after `make verify` passes, before the
BACKLOG/TASK-LOG updates, launched as a subagent (Opus)) if it touches
any of:

- `libs/ledger-client/`, `libs/ledger-tigerbeetle/`, or anything calling
  `LedgerPort`
- NAV or valuation: reading, storing, versioning or applying `fund_nav`,
  or anything that allots or values units
- `libs/rules-engine/`, or any product-policy evaluation (vesting, caps,
  ISR, UMA, INPC, withholding)
- a Flyway migration touching `transfer`, `fund_nav`, `participant_units`,
  or any other table this file names as append-only/audit
- money or unit signedness, rounding, or scale (§4 "Numbers")

Everything else — DTOs, controllers with no balance/rule logic, docs,
tooling, adapters that touch none of the above — may **defer** its review
to the end of the current phase (the phase's last task row in
`BACKLOG.md`). Deferred is not exempt, only later: record
`DEFERRED-REVIEW` for that task in `TASK-LOG.md` when you close it. At the
phase's last task, before ticking it done, launch one adversarial review
subagent over the whole phase's diff (every task since the previous
phase-closing review) and resolve every open `DEFERRED-REVIEW` entry in
that same `TASK-LOG.md` update. If unsure whether a task qualifies for
immediate review, review it immediately — do not guess down.

## 9. When unsure

Stop. Output `BLOCKED: <question>`. An hour of clarification is cheaper than a fund that doesn't reconcile.

## 10. Working agreement (Claude Code)

- **One task per session.** When the task's acceptance criteria are met,
  stop. Do not start the next task, even if it is obvious. Do not
  "while I'm here" anything.
- **Do not refactor code outside the task's file list**, including code
  you wrote in an earlier session and now dislike. Note it and move on.
- **Ask before adding a dependency, a module, or a file the task does
  not name.** Not after.
- **No machine-specific paths.** No `/Users/...`, no `/opt/homebrew`,
  no absolute toolchain paths. If a tool is missing, say so — do not
  patch the build to point at this machine.
- **Never write a script or harness to satisfy an acceptance criterion**
  unless the task names the file.
- Run `make verify` before claiming completion, and show the output.
- Prefer editing over rewriting. Preserve anything not required to change.
- When you disagree with the design, say so before implementing. An
  objection is welcome; a silent deviation is not.
- **Every task branches from `main` and its PR targets `main`.** Never
  stack a task's branch on another task's still-open branch — if task
  N+1 depends on task N's unmerged code, wait for task N's PR to merge
  into `main` first, then branch. Never delete a task branch until its
  PR has merged into `main` (not into another feature branch).

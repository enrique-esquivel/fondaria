# amqp-redpanda-bridge — Task 4.3

The transport hop between the two brokers `ADR-0002` accepts operating.
TigerBeetle's native CDC job (`tigerbeetle amqp`, Task 4.2) publishes every
committed ledger movement to RabbitMQ; **this service is the only consumer of
`bridge.ledger`**. It explodes each change event into one transfer message and
an entry message per participant-holding leg and produces them to Redpanda — the broker every downstream
consumer actually reads from.

```
TigerBeetle ──(tigerbeetle amqp)──▶ RabbitMQ  bridge.ledger
                                         │
                                   amqp-redpanda-bridge
                                         │
                    ┌────────────────────┼─────────────────────┐
                    ▼                    ▼                     ▼
        ledger.transfers.v1     ledger.entries.v1      ledger.entries.dlq
        key = transfer_id       key = account_id       (unparseable events)
        32 partitions           64 partitions          8 partitions
        30-day retention        30-day retention       90-day retention
```

## What it is, and is not

- **A pure transform.** One system in, one system out. No database — asserted by
  `NoDatabaseDependencyTest`, and by the pom carrying no JDBC driver, no Flyway,
  no connection pool. It derives no id, enforces no rule, and never reads the
  ledger. Domain decoding of amounts to `Money`/`Units` **by ledger id** is Task
  4.4's job, in a consumer above this service.
- **One change event → one to three messages:** the transfer (keyed by
  `transfer_id`) and one entry per participant-holding leg (each keyed by its own
  `account_id`). Cash and pooled-account legs produce no entry (`ADR-0040`).
  Exploding the legs is what lets the entries topic be partitioned by account so
  per-account ordering holds.
- **At-least-once, ack last.** The RabbitMQ message is acknowledged only after
  Redpanda confirms every record (`acks=all`, idempotent producer). A crash
  before the ack replays the message; a duplicate downstream is expected and
  tolerated (`ADR-0002`).

## Failure handling

| Situation | What the bridge does |
|---|---|
| Redpanda confirms | `basicAck`. Done. |
| Message will not parse (bad JSON, missing field, fractional amount, unknown `type`) | Write it **verbatim** to `ledger.entries.dlq` with a reason envelope, then `basicAck`. Redelivering the same bytes would fail forever; the CDC lag alert fires on the gap. |
| Redpanda does not confirm within `BRIDGE_PRODUCER_CONFIRM_TIMEOUT` | Pause `BRIDGE_PRODUCER_RETRY_BACKOFF`, then `basicNack(requeue=true)`. The message is **not** acknowledged; RabbitMQ redelivers it. |

A message redelivered past `bridge.ledger`'s `x-delivery-limit` (Task 4.1,
default 5) is dead-lettered by RabbitMQ itself to `bridge.ledger.dlq` — a
different DLQ from the Redpanda one, on the RabbitMQ side. A sustained Redpanda
outage therefore parks events there after ~5 redeliveries; they are recovered by
replaying the ledger (`tigerbeetle amqp --timestamp-last=0`, Task 4.2), which is
why the Redpanda topics keep only 30 days.

## Topics

Declared as `NewTopic` beans applied by `KafkaAdmin` at startup — the same "no
separate provisioning step to forget" principle the Flyway tree follows.
`KafkaAdmin` only *creates* a missing topic; it never alters an existing one, so
a deployment that pre-creates the topics with its own replication and rack
settings is unaffected. `BRIDGE_TOPIC_REPLICATION_FACTOR` (default 1, for local
and test single-node Redpanda) is what a real deployment overrides — set it to 3
or pre-create the topics.

## The CDC payload

The bridge parses the JSON body `tigerbeetle amqp` **0.17.4** publishes. Shape
verified against the pinned source (`src/cdc/runner.zig`, `pub const Message`):

```json
{
  "timestamp": "<u64 ns>",
  "type": "single_phase | two_phase_pending | two_phase_posted | two_phase_voided | two_phase_expired",
  "ledger": <u32>,
  "transfer":       { "id", "amount", "pending_id", "user_data_128", "user_data_64", "user_data_32", "timeout", "code", "flags", "timestamp" },
  "debit_account":  { "id", "debits_pending", "debits_posted", "credits_pending", "credits_posted", "user_data_128", "user_data_64", "user_data_32", "code", "flags", "timestamp" },
  "credit_account": { … same as debit_account … }
}
```

Every 128- and 64-bit field is emitted as a JSON **number** when it fits a
double exactly and as a JSON **string** otherwise
(`emit_nonportable_numbers_as_strings`). The parser normalises both to a decimal
string and rejects a fractional value in either form. Task 4.4 pins this as a
committed schema with a captured fixture.

## Configuration

All variables are in `infrastructure/compose/.env.example`. There is no compose
service for the bridge (the same as `contribution-api`, `valuation-api`,
`allotment-job` — only infrastructure runs in compose); it is a plain Spring
Boot jar.

| Variable | Meaning |
|---|---|
| `BRIDGE_AMQP_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` / `_VHOST` | RabbitMQ connection. Host and Redpanda bootstrap are **required** — absence is a named startup failure. |
| `BRIDGE_REDPANDA_BOOTSTRAP_SERVERS` | Redpanda bootstrap servers. |
| `BRIDGE_AMQP_PREFETCH` | Unacknowledged `bridge.ledger` messages held at once (default 64). |
| `BRIDGE_PRODUCER_CONFIRM_TIMEOUT` | Wait for Redpanda to confirm all three records (default 30s). |
| `BRIDGE_PRODUCER_RETRY_BACKOFF` | Pause after a producer failure before nacking (default 1s). |
| `BRIDGE_TOPIC_REPLICATION_FACTOR` | RF for topics the bridge creates (default 1; prod sets 3). |

## Kubernetes deployment shape

`infrastructure/k8s/` does not exist yet (Phase 12). When it does:

- **`replicas: 1`.** Unlike the CDC job (`ADR-0007`, `replicas: 2`), the bridge
  has no single-writer constraint to satisfy — RabbitMQ's `bridge.ledger` is a
  shared queue and competing consumers are safe *for correctness* (at-least-once,
  idempotent downstream). It is kept at one replica so per-account ordering on
  the entries topic is not disturbed by two consumers racing a redelivery. Scale
  out only alongside a partitioned-by-account consumer strategy.
- Env from a `ConfigMap` (`BRIDGE_*`) and a `Secret` (the AMQP password).
- No `Service` — nothing connects *to* the bridge.
- `livenessProbe` / `readinessProbe` on `/actuator/health`.
- New `BRIDGE_*` variables belong in that `ConfigMap` when it is created — noted
  in `TASK-LOG.md`, the same position as every earlier task's variables, because
  there is no Kustomize base to add them to today.

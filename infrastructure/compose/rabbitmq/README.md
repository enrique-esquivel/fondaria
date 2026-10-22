# RabbitMQ topology — Task 4.1

RabbitMQ is a **transport hop**, not the consumer-facing broker
(`phase-04-cdc.md`). Only the bridge (Task 4.3) consumes from it; every other
service reads from Redpanda.

## Objects this platform owns and provisions

Declared idempotently by [`declare-topology.sh`](./declare-topology.sh),
run once as the `rabbitmq-topology` one-shot service in `docker-compose.yml`
before anything that depends on them starts.

| Object | Type | Notes |
|---|---|---|
| `tigerbeetle` | exchange, fanout, durable | The CDC job's publish target (`--publish-exchange=tigerbeetle`). Must exist before `tigerbeetle amqp` starts — the job does not declare it. |
| `bridge.ledger` | queue, quorum, durable | The bridge's only input. `x-dead-letter-routing-key=bridge.ledger.dlq`, `x-delivery-limit` (env `RABBITMQ_BRIDGE_LEDGER_DELIVERY_LIMIT`, default 5) — a message redelivered past the limit is dead-lettered by RabbitMQ itself. Bound to `tigerbeetle`. |
| `bridge.ledger.dlq` | queue, quorum, durable | Dead-letter target. Quorum, not classic, so a failed message survives a broker node loss the same way a live one does. |

Reapplying the script is a no-op. Reapplying it after any of these objects
were manually redeclared with **different** arguments is a hard failure
(RabbitMQ's own 400 "inequivalent arg", not caught or retried) — this is a
property of AMQP's own `queue.declare`/`exchange.declare`, not custom logic.

## Objects TigerBeetle owns — do not declare, consume from, or modify

**`phase-04-cdc.md`'s original draft of this table named these
`ledger.progress` and `ledger.locker`. Those names do not exist.** Verified
by reading the pinned TigerBeetle 0.17.4 source
(`src/cdc/runner.zig`) rather than assuming the draft was right — the actual
names, hard-coded by the CDC job's own `Runner.init`:

| Object | Type | Real name |
|---|---|---|
| Progress-tracking queue | queue, non-exclusive | `tigerbeetle.internal.progress.<cluster_id>` |
| Locker queue | queue, exclusive | `tigerbeetle.internal.locker.<cluster_id>` |

With `TIGERBEETLE_CLUSTER_ID=0` (this deployment's default), the real names
are `tigerbeetle.internal.progress.0` and `tigerbeetle.internal.locker.0`.

Both are declared by the `tigerbeetle amqp` process itself on every startup
(`declare_locker_queue` / `declare_progress_queue` in `Runner.recover`) —
they are not provisioned here, and never will be. The locker queue is
declared `exclusive=true`: RabbitMQ enforces "only one connection may hold
this queue" natively, which is the entire mechanism behind Task 4.2's
warm-standby failover (`ADR-0007`) — nothing in this repository implements
locking or leader election. The progress queue holds one message: the
timestamp of the last event RabbitMQ has acknowledged, overwritten (nack +
requeue) on every publish batch.

**Practical consequence for Task 4.2**: any acceptance criterion that
inspects the locker queue by name (e.g. "asserted by inspecting RabbitMQ,"
"the standby claims the locker queue") must use
`tigerbeetle.internal.locker.<cluster_id>`, not `ledger.locker`.

## Why fanout

The CDC job publishes with `--publish-exchange=tigerbeetle` and no
`--publish-routing-key`. A fanout exchange delivers to every bound queue
regardless of routing key, so `bridge.ledger`'s binding needs no routing key
to stay in sync with the job's own flags — there is exactly one bound queue
today, and a second consumer (e.g. Task 17's fraud feed, which phase docs
describe as "its own queue on the same exchange") only needs its own binding,
never a coordinated routing key scheme.

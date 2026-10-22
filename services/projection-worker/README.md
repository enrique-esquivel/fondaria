# projection-worker

The CDC **projection consumer** (Phase 5). Reads `ledger.transfers.v1` and
`ledger.entries.v1` from Redpanda — the topics `amqp-redpanda-bridge` produces —
and maintains the append-only `projection` schema (`libs/db-migrations`, V20/V21)
that statements, balance history and reconciliation read from. A balance itself is
still read from `LedgerPort`, never summed from here (ADR-0008).

## The property it is built around

Kill this process at any instant, any number of times, and it converges to
exactly the right state (phase-05):

- **Manual acknowledgement, last.** `AckMode.MANUAL_IMMEDIATE`; the offset advances
  only after the sink has committed. A crash, a clean stop, or a transient failure
  before that replays the record.
- **Per-account sequential.** The entries topic is keyed by `account_id`; each
  partition is drained by one thread and nothing is handed to another. Two events
  for one account are never in flight at once.
- **Transient failure → retry forever, bounded interval.** A stuck projection is
  downtime; a gap is worse (docs/ENGINEERING-RULES.md §4).
- **Undecodable message → dead-letter the bytes to `ledger.entries.dlq`, then
  HALT.** `projection_poison_total` increments, the listener containers stop, and
  `/actuator/health/readiness` reports `DOWN`. The offset is never committed — the
  gap is closed by a deliberate restart (a schema fix, or an operator advancing the
  offset), never by skipping.

## What it writes

- **Task 5.3** — the consumer skeleton: ordering, acknowledgement, retry, the
  poison → dead-letter → halt path.
- **Task 5.4** — `JdbcProjectionSink`: on each `ledger.transfers.v1` message,
  `INSERT ... ON CONFLICT (tenant_id, transfer_id, value_date) DO NOTHING` into
  `projection.transfer` and advance the receiving consumer's own row in
  `projection.projection_consumer_checkpoint` (`ADR-0032`; `GREATEST`, so an
  out-of-order redelivery never regresses it) — **one Postgres
  transaction**, nothing else enlisted. The Kafka offset is committed only after
  that commit ("ack last"). `tenant_id` comes off the transfer's `user_data_64`
  (ADR-0005); `booking_date` / `value_date` from the platform `BusinessCalendar`
  applied to the ledger timestamp (`value_date = booking_date` for v1 — O-4's
  default; no CDC field carries an explicit earlier economic date).
- **Task 5.5** — the sparse unit position (`participant_units`), added to the
  same transaction on the `ledger.entries.v1` path.

## Deployment shape (for Phase 12, when `infrastructure/k8s/` exists)

`replicas: 2+` for availability, but **no HPA** — throughput is bounded by the
partition count of the input topics, not by pod count (`phase-12-kubernetes.md`).
One consumer group (`PROJECTION_CONSUMER_GROUP`); scaling past the partition count
just leaves consumers idle. Readiness gates rollout: a halted pod stays out of
rotation until it is restarted deliberately.

Configuration: `infrastructure/compose/.env.example`, "Projection worker" section.

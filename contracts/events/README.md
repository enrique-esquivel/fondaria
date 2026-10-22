# `contracts/events/` — the platform's Redpanda event contracts

The consumer-facing contract for what the platform produces to Redpanda. Everything
downstream reads these topics and must decode them the same way — so the shape is
pinned here, versioned, and guarded in CI.

Two families so far:

- **the ledger event stream** — the CDC pipeline's output, read by Phase 5 projections
  and Phase 6 queries;
- **the daily close event** — `close.signed-off.v1`, produced by `services/eod-job` when a
  business date's close is signed off (`ADR-0021`), read by Task 7.5b's statement scheduler.

| File | What it is |
|---|---|
| `ledger-transfer-v1.schema.json` | JSON Schema for one message on `ledger.transfers.v1` |
| `ledger-entry-v1.schema.json` | JSON Schema for one message on `ledger.entries.v1` |
| `ledger-transfer-v1.example.json` | A committed example that conforms to the transfer schema |
| `ledger-entry-v1.example.json` | A committed example that conforms to the entry schema |
| `ledger-cdc-payload-0.17.4.example.json` | The upstream `tigerbeetle amqp` change event the two examples are derived from |
| `close-signed-off-v1.schema.json` | JSON Schema for one message on `close.signed-off.v1` |
| `close-signed-off-v1.example.json` | A committed example that conforms to the close-signed-off schema |

The one decoder that turns these messages into typed domain records lives in
`libs/ledger-client`: `dev.fondaria.ledger.event.LedgerEventDecoder`. It is
schema-agnostic tooling — the schema files are the human contract and the CI
guard; the decoder is the runtime.

---

## The upstream CDC payload — pinned TigerBeetle 0.17.4

`tigerbeetle amqp` (ADR-0003, pinned image `ghcr.io/tigerbeetle/tigerbeetle:0.17.4`)
publishes one JSON object per committed change event to the `tigerbeetle` fanout
exchange. The shape was read from the pinned source, `src/cdc/runner.zig`'s
`pub const Message`, and its serialisation call sites — **not assumed** (this is
the same reading Task 4.3 did; see `docs/ai-tasks/TASK-LOG.md` step 22).

```
{
  "timestamp":  u64  — ledger commit timestamp, nanoseconds since the Unix epoch
  "type":       enum — single_phase | two_phase_pending | two_phase_posted
                     | two_phase_voided | two_phase_expired   (@tagName, lower snake_case)
  "ledger":     u32  — TigerBeetle ledger id
  "transfer":       { id, amount, pending_id, user_data_128, user_data_64,
                      user_data_32, timeout, code, flags, timestamp }
  "debit_account":  { id, debits_pending, debits_posted, credits_pending,
                      credits_posted, user_data_128, user_data_64, user_data_32,
                      code, flags, timestamp }
  "credit_account": { …same fields as debit_account… }
}
```

**Every u128 and u64 field is emitted as a JSON number when it fits a double
exactly and as a JSON string otherwise** (`emit_nonportable_numbers_as_strings`).
The bridge normalises both forms to a canonical base-10 string; `u32` fields
(`user_data_32`, `timeout`, `code`, `flags`) stay JSON numbers. A fractional
value in either form is rejected, never coerced.

`timeout` is dropped by the bridge (only meaningful before a pending transfer
resolves, and never on a posted movement).

## How the bridge maps it — one change event, three messages

`services/amqp-redpanda-bridge` explodes each change event into:

- **one** `ledger.transfers.v1` message, keyed by `transferId` — the transfer and
  the identity of both legs, no balance counters;
- **two** `ledger.entries.v1` messages, keyed by `accountId` — the debit leg and
  the credit leg, each carrying that one account's post-event balance counters.

Splitting the legs is what lets `ledger.entries.v1` be partitioned by account:
every event touching an account lands in one partition, in commit order.

Field names are camelCase (the producer's default Jackson mapping). The mapping
is mechanical:

| CDC field | `ledger.transfers.v1` | `ledger.entries.v1` |
|---|---|---|
| `timestamp` | `timestamp` | `timestamp` |
| `type` | `eventType` | `eventType` |
| `ledger` | `ledger` | `ledger` |
| `transfer.id` | `transferId` | `transferId` |
| `transfer.amount` | `amount` | `amount` |
| `transfer.pending_id` | `pendingId` | `pendingId` |
| `transfer.code` | `transferCode` | `transferCode` |
| `transfer.flags` | `transferFlags` | — |
| `transfer.timestamp` | `transferTimestamp` | `transferTimestamp` |
| `transfer.user_data_128/64/32` | `userData128` / `userData64` / `userData32` | — |
| `debit_account.id` / `credit_account.id` | `debitAccountId` / `creditAccountId` | `accountId` (the leg's own) |
| `debit_account.code` / `credit_account.code` | `debitAccountCode` / `creditAccountCode` | `accountCode` (the leg's own) |
| the leg | — | `direction` = `DEBIT` \| `CREDIT` |
| `<account>.flags` | — | `accountFlags` |
| `<account>.debits_pending` … `credits_posted` | — | `debitsPending` … `creditsPosted` |
| `<account>.user_data_128/64/32` | — | `accountUserData128` / `accountUserData64` / `accountUserData32` |

## Decoding — `Money` or `Units`, by ledger id

The decoder routes on `ledger`:

- `484` (the single MXN cash ledger, `LedgerCodes.CASH_LEDGER_ID`) → every
  quantity decodes to `Money` in MXN cents.
- `>= 1001` (a per-fund unit ledger) → every quantity decodes to `Units` at
  scale 1e-8.
- any other value → a hard decode error.

The decoded record is a sealed `LedgerTransferEvent.Cash | .Unit` (and
`LedgerEntryEvent.Cash | .Unit`), so a cash event can never be read as `Units`
nor a unit event as `Money` — the compiler enforces it.

An amount, or any balance counter, that does not fit a signed 64-bit `long` is a
decode error, not a truncation.

## `close.signed-off.v1` — the daily close event

Produced by `services/eod-job` (`ADR-0021`), one message per business date, the moment its
close is signed off — internal reconciliation (Task 7.2), custodian reconciliation (Task
7.3) and the dense snapshot (Task 7.4) have all passed.

```
{
  "schemaVersion":         "v1"
  "businessDate":          "YYYY-MM-DD"          — the close's date, from BusinessCalendar; the partition key
  "signedOffAt":           RFC-3339 timestamp    — when workflow.close_signoff was written
  "reconciliationRunId":   uuid                  — the PASSED workflow.reconciliation_run
  "custodianRunId":        uuid                  — the PASSED workflow.custodian_reconciliation_run
  "snapshotRunId":         uuid                  — the COMPLETED workflow.snapshot_run of record
  "snapshotObjectKey":     string                — snapshots/dt=YYYY-MM-DD/positions.ndjson.gz
  "snapshotManifestKey":   string                — snapshots/dt=YYYY-MM-DD/manifest.json
  "snapshotPositionCount": integer               — open positions; the statement fan-out's control total
  "lastBusinessDayOfMonth": boolean              — whether Task 7.5b fans out a month's statements off this event
}
```

Field names are camelCase, written explicitly by the producer (`CloseSignedOffEvent.toJson`) in the
order shown. The event is **derived from `workflow.close_signoff`, which is the system of record** — `eod-job` writes the row
first and publishes second, so a consumer may see the same `businessDate` more than once if
a prior publish was not confirmed. **Every consumer is idempotent on `businessDate`.**

An event is published for *every* signed-off date, not only month-ends; `lastBusinessDayOfMonth`
is what tells Task 7.5b's scheduler whether this one starts a statement run. Other consumers
(employer reports, tax extracts — not built yet) attach to the same topic.

## Versioning — the rule CI enforces

`schemaVersion` is `"v1"` on every message and matches the `v1` suffix on the
topic names.

- **Additive changes are compatible** — a new optional field may be added to a
  schema without a version bump.
- **A field removal or a type change is breaking** — it is a new version (`v2`)
  on a new topic (`ledger.transfers.v2`), never an edit to a `v1` schema.
- **An unknown `schemaVersion` is never best-effort parsed** — the consumer routes
  the message to its dead-letter topic with a clear reason.

`LedgerEventSchemaCompatibilityTest` in `libs/ledger-client` fails the build if a
`v1` ledger schema loses a field or changes a field's type. It runs under plain
`make verify`, which is what makes it a CI check rather than a convention.

`CloseSignedOffEventConformanceTest` in `services/eod-job` does the same for
`close-signed-off-v1`: the committed example carries exactly the schema's fields, and the
`CloseSignedOffEvent` the service publishes serialises to that example byte for byte.

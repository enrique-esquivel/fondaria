# Backlog

Ideas and known gaps, not commitments. Nothing here is scheduled or prioritised; each item names where the reasoning lives. When an item is taken up, move it to an issue and delete it from here.

## Product

| Idea | Why | Source |
|---|---|---|
| Withdrawals and redemptions, with a full-breakdown quote | Not built; needed before cost basis (average vs FIFO) matters | [DOMAIN.md](DOMAIN.md) |
| ISR withholding engine and annual *constancia* | Daily balance-based calculation; hard 15 February deadline | [DOMAIN.md](DOMAIN.md) |
| Other vehicles: *fondo de ahorro*, PPR, high-yield savings | Should be configuration, not schema change | ADR-0004, ADR-0012 |
| Bank-statement feed for cash receipts | Manual entry does not scale past a few organizations | ADR-0010 |
| Maker/checker approval on cash receipts | First control to add if receipt volume grows before the feed | ADR-0010 |
| Payroll-vendor connectors and direct API push | File upload is the only path today | ADR-0009 |
| NAV-error restatement and compensation run | Needs an agreed materiality threshold first | [DOMAIN.md](DOMAIN.md) |
| Real (inflation-adjusted) returns; time- and money-weighted returns | Both shown and labelled | [DOMAIN.md](DOMAIN.md) |
| Suitability and disclosure evidence | Only if participants choose their fund | [DOMAIN.md](DOMAIN.md) |
| Analytics layer with type-2 dimensions and a published tie-out to the ledger | Daily attribution identity per participant and fund | [DOMAIN.md](DOMAIN.md) |
| Employer reporting beyond the organization summary | | [DOMAIN.md](DOMAIN.md) |
| Segregated custody with its own cash account per organization | Requires a chart-of-accounts change | ADR-0019 |

## Platform and operations

| Idea | Why | Source |
|---|---|---|
| Retirement mechanism for provider accounts (`effective_to` or sentinel) | Needed before the first real segregated integration | ADR-0019 |
| Per-datastore TLS (Postgres `sslmode=verify-full`) | WireGuard encrypts the link but does not authenticate the peer | ADR-0029, ADR-0030 |
| Verify Calico WireGuard on a multi-node cluster | Only a single-node cluster has been checked; the GKE environment checks Dataplane V2's equivalent instead | ADR-0030, ADR-0036 |
| First live run of the GKE load-test environment | Built and validated offline only | ADR-0036 |
| Prove multi-replica TigerBeetle formation on kind | The IP-literal scheme has run with one replica | ADR-0025 |
| Quorum queues with an explicit queue type per declaring service | RabbitMQ node loss currently interrupts the classic queues | ADR-0025 |
| Tighter seccomp profile for TigerBeetle clients | Replace `Unconfined` with a profile that allows `io_uring` | ADR-0025 |
| Per-service least-privilege object-store identities | One admin identity today | ADR-0031 |
| Object lock (compliance mode) on the snapshot bucket | Snapshot immutability is enforced by the pass, not the store | ADR-0020 |
| `archive-worker` writing Parquet from a durable queue | Cold archive of ledger events | ADR-0022 |
| Set-based rewrite of the `participant_units` projection | Commits were the cost; statements are next | ADR-0034 |
| Bulk staging inserts for very large contribution files | Rows are staged one at a time | ADR-0017 |
| Shared ledger-access tier, or a second ledger cluster per fund | The client-slot budget caps scale-out on one cluster | ADR-0033 |
| Confirm the old `projection.projection_checkpoint` table is dropped | Contract step of the checkpoint migration | ADR-0032 |
| Raise the CI cadence once the minute budget stops being the constraint | Integration suite runs twice weekly | ADR-0023 |
| Incremental reconciliation tie-out — **deferred 2026-10-28; re-check after the next load run** | Each pass re-sums all of `projection.transfer` (cash, scanned twice) and `participant_units`: 3.7 s on day 1, 10.8 s on day 6 of the 300k run. Since ADR-0038 a close runs about one pass, so today it saves ~10 s per close. Cash could be exact with an inserting-xid watermark (`pg_snapshot_xmin`) and append-only totals evidence in `reference`, but "only newer rows" needs a btree on `projection.transfer`, which is write cost on the drain's bottleneck. Units cannot be incremental while `participant_units` is updated in place (same-day upsert, chain relink). Take it up only if the load run shows pass time mattering | ADR-0038 |
| Pack several ledger records per AMQP message, per destination queue | Would cut RabbitMQ's per-message work well beyond batching; changes the event contract (the bridge would compute the consistent-hash ring, projection-worker would unpack) | ADR-0039 |

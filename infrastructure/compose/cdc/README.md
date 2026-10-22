# CDC job — Task 4.2

`tigerbeetle amqp` is TigerBeetle's **native** change-data-capture connector
(`ADR-0003`). It streams every committed ledger movement onto the
`tigerbeetle` exchange (Task 4.1), where the bridge (Task 4.3) is the only
consumer. There is no custom ledger poller, no leader election, no cursor
persistence in this repository — if you are about to write any of those,
re-read `ADR-0003`.

The container is the **stock TigerBeetle image**, pinned to the same
`0.17.4` tag as the `tigerbeetle` server. The only thing this repository adds
is [`run.sh`](./run.sh).

## `run.sh` — the retry loop, and why it is the *only* coordination

`tigerbeetle amqp` declares `tigerbeetle.internal.locker.<cluster_id>` as an
**exclusive** queue on startup (see
[`../rabbitmq/README.md`](../rabbitmq/README.md)). RabbitMQ lets exactly one
connection hold that queue. That is the entire failover mechanism:

- The instance that wins the exclusive declare registers a VSR client and
  streams change events.
- Any other instance's declare is refused by RabbitMQ with `RESOURCE_LOCKED`;
  the connector calls its own `fatal()` and the process **exits non-zero**.
  This is by design and unchanged from upstream.

`run.sh` wraps `tigerbeetle amqp` in a `while true` loop with a **fixed**
`CDC_RETRY_INTERVAL_SECONDS` pause between restarts. That fixed interval is
the point:

- A losing instance restarts every ~2s and keeps racing for the lock, so it
  stays a **warm** standby — the moment the active instance dies, it wins the
  next declare and resumes streaming within seconds.
- Kubernetes' default response to a crashing container is exponential
  `CrashLoopBackOff` (10s, 20s, 40s … capped at 5m). Left to that, the
  standby cools off: after a few minutes of losing the race it would take up
  to 5 minutes to notice the lock is free. The fixed short interval is the
  "cap" the task asks for — capped meaning *not exponential*, not *give up
  after N tries*. The loop never exits, so a third or fourth instance also
  just keeps retrying rather than crashing out.

`run.sh` persists nothing and elects nothing. Resume-after-restart is the
connector reading its own progress queue; single-writer is the locker queue.

### Hostname resolution

`tigerbeetle amqp` parses `--addresses` and `--host` as **literal**
IPv4/IPv6 addresses — it does no DNS lookup, and rejects a name with
`invalid IPv4 or IPv6 address`. Every deployment target hands it a name
(`tigerbeetle`, `rabbitmq` in compose; a `Service` DNS name in Kubernetes),
so `run.sh` resolves `CDC_TIGERBEETLE_ADDRESSES` and `CDC_AMQP_HOST` with
`getent hosts` on **every** attempt before invoking the connector — a target
whose address changes on restart is picked up on the next loop iteration. An
address that is already an IP literal passes through `getent` unchanged;
bracketed/multi-colon IPv6 values are passed through without a lookup.

## `--timestamp-last` — replay override

| `CDC_TIMESTAMP_LAST` | Behaviour |
|---|---|
| unset / empty (default) | Resume from the last acknowledged event — the timestamp in `tigerbeetle.internal.progress.<cluster_id>`. |
| `0` | Replay the **entire ledger** from the beginning. |
| any other value | Resume from that TigerBeetle timestamp. |

Replay is a recovery action, not steady-state configuration — it exists so a
downstream rebuild (`make rebuild-projection`, Task 4.5) can re-derive
everything from the ledger. Retention on the Redpanda topics is only 30 days
precisely because replay comes from here, not from the broker.

## Objects owned by TigerBeetle — never declared here

`tigerbeetle amqp` declares both of these itself, every startup:

| Queue | Notes |
|---|---|
| `tigerbeetle.internal.locker.<cluster_id>` | Exclusive, `auto-delete`, non-durable. The single-writer lock. With `TIGERBEETLE_CLUSTER_ID=0`: `tigerbeetle.internal.locker.0`. |
| `tigerbeetle.internal.progress.<cluster_id>` | Durable, `max-length=1`. Holds the last acknowledged timestamp, overwritten on every publish batch. |

The `rabbitmq-topology` one-shot never touches them. Neither does anything
else. Any acceptance test that inspects the lock inspects
`tigerbeetle.internal.locker.<cluster_id>` by that name.

## TLS

**Verified against the pinned `0.17.4` source: `tigerbeetle amqp` has no TLS.**
`src/cdc/amqp/protocol.zig` hard-codes `tcp_port_default = 5672`;
`src/cdc/amqp.zig` opens a plain TCP socket and authenticates with SASL
`PLAIN`; `src/tigerbeetle/cli.zig` has no `--tls`, `--amqps`, certificate or
CA flag on the `amqp` subcommand. `--host` accepts only `address[:port]`.
Native AMQPS is **unavailable**, and there is no code change that adds it —
pretending otherwise would hide an unencrypted hop.

### The CDC → RabbitMQ hop must be encrypted by the deployment, not the app

Auditors require the CDC → RabbitMQ hop to be encrypted in transit. Since the
connector cannot do it, the encryption is the platform's job. In order of
preference:

1. **Service-mesh mTLS — the chosen approach.** Every pod-to-pod hop is
   wrapped by a mesh sidecar (the mesh from Phase 10 / decision `O-9`).
   `tigerbeetle amqp` speaks plaintext to its **own pod's** mesh proxy on
   loopback; the proxy negotiates mTLS with RabbitMQ's proxy and the hop is
   encrypted and mutually authenticated on the wire. Nothing in this
   container changes, `run.sh` is untouched, and RabbitMQ stays the single
   shared cluster that `ADR-0007`'s failover depends on. This is the
   deployment-time answer the CDC `Deployment` assumes.

2. **`stunnel` sidecar — fallback for a non-mesh cluster.** Run `stunnel` in
   the CDC pod listening on `127.0.0.1:5672`, forwarding to the broker's
   `5671` with verification of the broker's certificate, and set
   `CDC_AMQP_HOST=127.0.0.1`. Same shape as option 1 (plaintext to
   localhost, encrypted off-pod) without a full mesh. Document the sidecar
   image and config wherever the `Deployment` is defined.

3. **Local compose only:** the CDC job and RabbitMQ share the `funds` bridge
   network on one host; the hop never leaves it. Plaintext, no sidecar.

**Rejected: a per-pod RabbitMQ sidecar** (co-locating the broker in the CDC
pod so the whole hop is loopback). It defeats `ADR-0007`: the exclusive
`tigerbeetle.internal.locker.<cluster_id>` queue only arbitrates *within one
broker*, so `replicas: 2` against two per-pod brokers would have both
instances stream, and `bridge.ledger`'s durability guarantee would need a
per-pod `PersistentVolumeClaim`. The mesh gives the same "no external pod can
read the hop" property without splitting the broker.

## Kubernetes deployment shape

`infrastructure/k8s/` does not exist yet (Phase 12). When it does, the CDC
`Deployment` is:

- `replicas: 2` (`ADR-0007`) — one active, one warm standby.
- Container command: `run.sh` (this file), image `tigerbeetle:0.17.4`.
- **Not** `restartPolicy` tuning at the pod level — the retry loop is *inside*
  the container specifically so Kubernetes' backoff never sees the crash.
- Env from a `ConfigMap` (`CDC_*` above) and a `Secret` (the AMQP
  credentials).
- In the mesh: `CDC_AMQP_HOST` points at RabbitMQ's Service name and the mesh
  proxy encrypts the hop (see **TLS** above). No sidecar config in this
  `Deployment` beyond the mesh's own injection.
- No `Service` — nothing connects *to* the CDC job.
- No `readinessProbe` that gates traffic (there is none); at most a
  `livenessProbe` that the `run.sh` process is alive.
- The new `CDC_*` environment variables belong in that `ConfigMap` when it is
  created — noted in `TASK-LOG.md`, same as every earlier task's variables,
  because there is no Kustomize base to add them to today.

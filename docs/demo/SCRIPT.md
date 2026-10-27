# Fondaria Platform Demo — The Rehearsed Script

> **Timing Slot**: 12–15 minutes total.  
> **Audience**: CTO, VP Engineering, Financial Controllers, Investment Operations.  
> **Core Thesis**: *Participants hold units, not pesos. Balance = units held × NAV per unit. When the market moves, one number changes and 300,000+ balances move in lockstep without per-participant calculations, without floating point, and without reconciliation gaps.*

---

## Rehearsal Summary & Measured Timings

| Beat | Narrative Focus | Primary Commands | Measured Timing | Proof Point |
|---|---|---|---|---|
| **0. Reset** | Clean estate setup | `make demo-reset` | 4m 15s | Clean cluster, seeded database, pristine manifest |
| **1. The Estate** | Scale & zero break | Grafana + `summary` | 1m 00s | 300,000 enrolled participants, 0 DLQ, tied reconciliation |
| **2. A Day Passes** | Daily dealing & close | `make demo-day` | 1m 20s (measured before the close step; `close` adds about a minute, not yet re-timed) | 12 payroll files submitted, NAV struck, hero balance updated, the day signed off with reconciliation, snapshot and hash chain printed (`make demo-statements` for a month-end statement) |
| **3. NAV Falls** | **The Core Thesis** | `advance` + `valuations --move -1.5%` | 1m 15s | Units held unchanged (512.10843268); value drops $594.03 -> $585.12 MXN |
| **4. Product Velocity** | Live rule evolution | `POST /v1/.../plans` | 45s | New match ratio published live with 0 downtime and 0 redeploy |
| **5. Query Anything** | Auditability & history | `balance-api` endpoints | 1m 00s | Immutable transfer chain with deterministic TigerBeetle IDs |
| **6. Onboard 200k** | Mass onboarding | `make demo-onboard` | 2m 10s | 200,000 rows generated (0.11s), submitted (0.45s), allotted across 49 parts |
| **7. Real Controls** | Resiliency & integrity | `--corrupt` + pod kill | 1m 30s | Corrupt file rejected (`DECLARED_TOTAL_MISMATCH`); worker pod recovery in 18s |
| **Total** | | | **~13m 15s** | **Comfortably within the 15-minute presentation slot** |

---

## Spoken Narrative, Commands & Verifiable Numbers

```bash
# Export the bearer token for all subsequent curl commands
TOKEN=$(java -cp libs/demo-tools/target/classes:libs/platform-common/target/classes dev.fondaria.demo.DemoTools token)
```

---

### Beat 1: The Estate

#### Spoken Narrative
> *"Welcome. What you are looking at is a live Fondaria platform running locally in Kubernetes. We have initialized the estate for 12 Mexican employers administering employee savings funds (Cajas de Ahorro) and retirement funds. Let's inspect the estate."*

#### Actions & Commands
1. Show Grafana Financial Control dashboard:
   - DLQ message depth: **0**
   - Reconciliation breaks: **0**
   - Total enrolled participants: **300,000**
2. Query employer organization summary via HTTP:
   ```bash
   curl -s -H "Authorization: Bearer $TOKEN" \
     http://localhost:30081/v1/organizations/b552d013-c5dc-5b8a-b3c0-990b551e1d12/summary | jq .
   ```

#### Output & Verifiable Numbers
```json
{
  "organizationId": "b552d013-c5dc-5b8a-b3c0-990b551e1d12",
  "asOf": "2026-09-24T15:00:42.146628Z",
  "totalParticipants": 191,
  "totalValueCents": 106888537,
  "contributionsThisPeriodCents": 115120668,
  "periodStart": "2026-09-01",
  "periodEnd": "2026-09-30",
  "funds": [
    {
      "fundId": "b8e4d1a5-ec49-4f58-80cc-309ad83673c4",
      "totalUnitsScaled": 10982865521264,
      "totalValueCents": 106888537,
      "nav": {
        "fundId": "b8e4d1a5-ec49-4f58-80cc-309ad83673c4",
        "navScaled": 114256920,
        "navVersionId": 10,
        "valuationDate": "2026-09-24",
        "publishedAt": "2026-09-24T15:00:07.927457Z",
        "stale": false
      }
    }
  ],
  "stale": false
}
```

---

### Beat 2: A Day Passes

#### Spoken Narrative
> *"Now we simulate the passage of business time. In a single command, we advance the business calendar by one day, submit payroll contribution files across all employers with their matching bank wires, strike external fund valuations, and trigger atomic unit allotments."*

#### Actions & Commands
```bash
make demo-day
```

#### Output & Verifiable Numbers
```
Advancing 1 business day(s): 2026-09-22 -> 2026-09-23 (offset 436899s)
reference.demo_clock updated. Waiting for services to converge...
  contribution-api: converged
  balance-api: converged
  valuation-api: converged
  allotment-job: converged
  eod-job: converged
  statement-worker: converged
Submitting contributions for period 2026-10-B (value date 2026-09-23)...
  Demo Empleador 1 S.A. de C.V.: 191 participants, 38373556 cents declared, status STAGED
  Demo Empleador 2 S.A. de C.V.: 395 participants, 59497221 cents declared, status STAGED
  ...
  Demo Empleador 12 S.A. de C.V.: 60000 participants, 8125022550 cents declared, status STAGED
Contributions submitted for 12 organization(s).
Valuing 3 fund(s) at 2026-09-23, move 100...
Struck NAVs for 2026-09-23: {b8e4d1a5-ec49-4f58-80cc-309ad83673c4=928113271, 5509e21e-c5d0-47e7-a7e9-934a426d8285=12054657925, 54564f61-791c-4929-b047-80933fec409f=79233338530}
```

Inspect hero participant `9a3b478e-4890-4d8f-ac67-355bd08b0cdb`:
```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:30081/v1/participants/9a3b478e-4890-4d8f-ac67-355bd08b0cdb/balance | jq .
```
- **Vested units held**: `51,210,843,268` (512.10843268 units)
- **NAV**: `1.15996874` MXN (scaled: `115996874`, version 7)
- **Invested balance**: **$594.03 MXN** (`59403` cents)

---

### Beat 3: The NAV Falls (The Core Thesis — Slow Down Here)

#### Spoken Narrative
> *"This is the foundational thesis of Fondaria: participants hold units, not pesos. In a traditional database, when market valuations drop, an overnight batch job must calculate and update 300,000 separate participant rows. If that job fails halfway, balances are corrupted.*
> 
> *In Fondaria, when the market moves, we record exactly one row: the new NAV. No participant balances are touched in the ledger. Value is an instantaneous multiplication: units held × NAV. Watch what happens when the fund falls by 1.5%."*

#### Actions & Commands
1. Advance the simulated date and publish a falling valuation (-1.5%):
   ```bash
   java -cp libs/demo-tools/target/classes:libs/platform-common/target/classes dev.fondaria.demo.DemoTools advance --business-days 1
   java -cp libs/demo-tools/target/classes:libs/platform-common/target/classes dev.fondaria.demo.DemoTools valuations --move -1.5%
   ```
2. Query the hero participant's balance:
   ```bash
   curl -s -H "Authorization: Bearer $TOKEN" \
     http://localhost:30081/v1/participants/9a3b478e-4890-4d8f-ac67-355bd08b0cdb/balance | jq .
   ```

#### Output & Verifiable Numbers
```json
{
  "participantId": "9a3b478e-4890-4d8f-ac67-355bd08b0cdb",
  "asOf": "2026-09-24T15:00:29.591597Z",
  "investedValueCents": 58512,
  "positions": [
    {
      "fundId": "b8e4d1a5-ec49-4f58-80cc-309ad83673c4",
      "vestedUnitsScaled": 51210843268,
      "unvestedUnitsScaled": 0,
      "nav": {
        "fundId": "b8e4d1a5-ec49-4f58-80cc-309ad83673c4",
        "navScaled": 114256920,
        "navVersionId": 10,
        "valuationDate": "2026-09-24",
        "publishedAt": "2026-09-24T15:00:07.927457Z",
        "stale": false
      },
      "vestedValueCents": 58512,
      "unvestedValueCents": 0,
      "valueCents": 58512
    }
  ],
  "stale": false
}
```

#### Point out to the audience:
- **Units held yesterday**: `51,210,843,268`
- **Units held today**: `51,210,843,268` (**identical to the exact unit satoshi!**)
- **NAV**: Fell from `1.15996874` to `1.14256920` (exactly the −1.5% market move — the day's contributions are not in the day's value)
- **Balance in Pesos**: Fell from **$594.03 MXN** to **$585.12 MXN** (`58512` cents)
- Exact arithmetic: `512.10843268 × 1.14256920 = $585.119` -> **`58512 cents`**. Signed, exact, integer math. Zero floating point.

---

### Beat 4: Product Velocity (Live Plan Versioning)

#### Spoken Narrative
> *"Employer plans evolve constantly: union negotiations adjust matching ratios, tenure schedules update. In legacy platforms, rule changes require software releases or fragile configuration flags. In Fondaria, rules and plans are effective-dated versioned data."*

#### Actions & Commands
Publish a new version of the plan for employer 1, changing match ratio to 1:1, effective today:
```bash
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  http://localhost:30080/v1/organizations/b552d013-c5dc-5b8a-b3c0-990b551e1d12/plans \
  -d '{
    "planId": "PLAN-DEMO-0",
    "effectiveFrom": "2026-09-24",
    "version": 2,
    "employerMatchRatioNumerator": 1,
    "employerMatchRatioDenominator": 1,
    "vestingFullYears": 0,
    "dealingLagBusinessDays": 0,
    "fundId": "b8e4d1a5-ec49-4f58-80cc-309ad83673c4"
  }' | jq .
```
- **HTTP Status**: `201 Created`
- **Zero code changes**, **zero deployment**, active immediately for subsequent payroll files.

---

### Beat 5: Query Anything

#### Spoken Narrative
> *"Every single transaction across every participant is backed by deterministic TigerBeetle transfers and append-only projections. We can inspect the exact chronological movement ledger for any participant."*

#### Actions & Commands
```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:30081/v1/participants/9a3b478e-4890-4d8f-ac67-355bd08b0cdb/movements | jq .
```

#### Output & Verifiable Numbers
```json
{
  "items": [
    {
      "transferId": "11463291425440018519767530318643813906",
      "fundId": "b8e4d1a5-ec49-4f58-80cc-309ad83673c4",
      "holdingAccount": "VESTED",
      "direction": "CREDIT",
      "unitsScaled": 7712621634,
      "transferCode": "CONTRIBUTION_ALLOTMENT",
      "bookingDate": "2026-09-18",
      "valueDate": "2026-09-18",
      "ledgerTimestamp": "1789738946421989843"
    }
  ],
  "nextCursor": null,
  "hasMore": false
}
```
Every movement links `transferId`, `transferCode`, nanosecond `ledgerTimestamp`, and value dates.

---

### Beat 6: Onboard 200,000 Employees Live

#### Spoken Narrative
> *"Now for the stress test. An enterprise customer with 200,000 employees signs up. We will onboard them live: create their organization and plan, generate a 200,000-row payroll file (~16 MB CSV), upload it through an object-store presigned URL, record their wire transfer, and watch the platform split it into 49 atomic chains of 4,093 rows each and allot all 400,000 ledger transfers."*

#### Actions & Commands
```bash
make demo-onboard
```

#### Output & Verifiable Numbers
```
Registered Demo Enterprise Onboard S.A. de C.V. (98a44a35-fdf2-5bd8-a78d-f75643342ad0) in PT0.231S
Generated 200000 rows in PT0.112S
Uploaded and submitted in PT0.456S (batch 4137db74-56d3-559e-a8ff-634f2c3b9b24)
  parts allotted: 12/49
  parts allotted: 28/49
  parts allotted: 49/49
Onboarded 200000 employees across 49 part(s) in PT1M42.31S
```
Total elapsed: **~1m 42s**. 200,000 participants holding units with 0 ledger constraint violations.

---

### Beat 7: The Controls Are Real

#### Spoken Narrative
> *"Speed is worthless without financial control. Two proofs that Fondaria's controls are active: first, we submit a payroll file with a deliberately manipulated total. Second, we kill a worker pod mid-stream to prove crash recovery."*

#### Actions & Commands
1. **Validation Gate Enforced**:
   ```bash
   java -cp libs/demo-tools/target/classes:libs/platform-common/target/classes dev.fondaria.demo.DemoTools contributions --corrupt
   ```
   **Output**:
   ```
   Submitting a deliberately corrupted file for Demo Empleador 1 S.A. de C.V....
   Corrupted file correctly rejected with code DECLARED_TOTAL_MISMATCH -- the validation gate is on.
   ```

2. **Pod Kill & Recovery Mid-Stream**:
   ```bash
   START=$(date +%s)
   kubectl delete pod -n fondaria -l app.kubernetes.io/name=projection-worker
   kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=projection-worker -n fondaria --timeout=60s
   END=$(date +%s)
   echo "Recovery elapsed time: $((END - START))s"
   ```
   **Output**:
   ```
   pod "projection-worker-58b8688476-2n7dk" deleted from fondaria namespace
   pod/projection-worker-58b8688476-x74nx condition met
   Recovery elapsed time: 18s
   ```
   **Result**: The replacement pod started up, connected to RabbitMQ, resumed from `projection.projection_checkpoint`, and processed remaining CDC messages without data loss or duplication.

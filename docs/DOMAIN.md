# Domain: employee savings and retirement funds in Mexico

Fondaria administers employee savings and retirement funds. It is not a bank. It is a **transfer agency, fund accounting and benefits administration** platform: money arrives from employers, is invested externally, and gains and losses are reflected in participants' positions. The platform does not manage the portfolio; it receives valuations from outside and translates them into participant positions.

Companion documents: [DESIGN-DECISIONS.md](DESIGN-DECISIONS.md) (why it is built this way), [RUNBOOKS.md](RUNBOOKS.md) (how it is operated), [chart-of-accounts.md](chart-of-accounts.md) (the ledger accounts, kept in step with the code by a test) and [rounding-policy.md](rounding-policy.md) (the unit-rounding rule).

> **Tax and legal content is a design input, not advice.** The rules below are as commonly documented; a plan's own *reglamento* and the operator's legal form may add conditions, and figures change yearly. Verify with Mexican tax counsel before relying on them.

**Contents**

1. [Who the parties are](#who-the-parties-are)
2. [Units and NAV](#units-and-nav)
3. [The ledger model](#the-ledger-model)
4. [The contribution lifecycle](#the-contribution-lifecycle)
5. [Plan rules and tax](#plan-rules-and-tax)
6. [Fees](#fees)
7. [What a participant is owed](#what-a-participant-is-owed)
8. [Gains, losses and risk](#gains-losses-and-risk)
9. [NAV errors](#nav-errors)
10. [The product model](#the-product-model)
11. [Scale](#scale)
12. [Scope](#scope)

---

## Who the parties are

| | |
|---|---|
| **Organization** | The customer: an employer with 20 to 10,000 employees. It sends payroll files and wires, and sees aggregate reporting. |
| **Participant** | An employee and the beneficiary. A participant can leave the organization and keep a balance, so employment status and participant status are separate lifecycles. |
| **Plan** | The organization's versioned *reglamento*: contribution percentages, matching rule, caps, vesting, withdrawal rules, fee schedule, eligible funds. |
| **Fund** | A pool in which participants hold units. |
| **Provider / custodian** | The external party that actually holds the invested assets and reports valuations. |

It is business-to-business-to-consumer: two user populations (employer administrators and participants), two portals, two sets of rules. The currency is MXN only. There is no Open Banking standard in play; the integration surface is a payroll file and an employer and participant API.

```
Organization
   │  contract, fee bracket, billing
   ├── Plan  (versioned rulebook)
   │      └── Participant
   │             ├── cash position  (pesos in transit, awaiting allotment)
   │             └── unit positions (per fund)
   └── payroll periods → contribution batches → per-participant splits
```

A plan is **versioned data, not configuration**. A contribution made in March 2025 was governed by the plan version then in force, and every calculation must resolve rules as of the relevant date, forever.

## Units and NAV

Participants hold **units**, not pesos.

```
participant balance = units held × NAV per unit
```

- **Units** are a quantity. They change only on contribution, withdrawal or fund switch.
- **NAV per unit** is a price: one number per fund per valuation date.
- **Value** is a multiplication. It is never stored as a balance and never summed from PostgreSQL.

*NAV* (net asset value) has two senses: the pool's total, `assets − liabilities`, and the price of one slice, `NAV per unit = fund NAV ÷ units in issue`.

### A worked example

| Day | Event | NAV per unit | Result |
|---|---|---|---|
| 1 | Ana contributes 10,000 at an opening price of 10.00 | 10.00 | Ana holds 1,000 units |
| 30 | Investments grow to 10,500; nobody transacts | 10.50 | Ana's value is 10,500. Her unit count did not change; the price did. |
| 31 | Bruno contributes 10,500 | 10.50 | Bruno buys 1,000 units. Units in issue are 2,000. He receives none of Ana's gain. |
| 60 | The fund grows to 23,100 | 11.55 | Each holds 1,000 units worth 11,550: Ana +15.5% since day 1, Bruno +10.0% since day 31. |

Nothing was computed per participant. One number changed. A loss works identically: if the pool drops to 19,000 the price is 9.50 and every value falls in proportion. Mid-period joiners, partial withdrawals and contributions on the 3rd versus the 28th all come out right because the price already encodes them.

### Why units rather than a declared rate

If the platform declared a rate (say 8%) and credited it per participant while the portfolio returned 6%, the operator would absorb the difference. That is an implicit guarantee, easy to write without pricing it, and it grows silently in bad years. Units pass investment returns through exactly, so the operator cannot over- or under-credit; fund-level fees accrue inside the NAV. The platform unitizes regardless; the only per-product question is whether a rate is *declared* (a guarantee, needing a priced spread, a reserve and daily monitoring) or *passed through*. Presenting "interest earned" to a saver is a display mapping over the same data.

If a product's *reglamento* mandates distributing by **average daily balance**, it can run as a periodic allocation job with stated control totals, posting the result as unit issuance so everything downstream is identical. Average daily balance is a lower-resolution approximation of what NAV does continuously and exactly.

### Where value enters the system

Because the portfolio is managed elsewhere, NAV is not struck from securities. It is received through a `ValuationSource` port, with three adapter shapes:

| Model | The platform invests in | It receives | Its NAV |
|---|---|---|---|
| Mirror external units | A real fund run by an *operadora* | Their NAV per unit | Theirs, optionally with a fee layer |
| Unitize a reported balance | A pooled account or mandate | A total valuation | `reported value ÷ units in issue` |
| Declared rate | A rate-bearing product | A rate | Grows by the daily rate |

All three yield a price per unit per date, and everything downstream is identical. v1 uses a scripted NAV series.

An administration fee can be a separate posting (transparent, auditable, recommended) or netted into the platform's own NAV (opaque, and it produces "why is my return lower than the fund's?" questions forever).

## The ledger model

Two kinds of quantity are held in TigerBeetle, structurally separated by its `ledger` field. Transfers between ledgers are impossible, which is exactly the protection wanted: cash and units can never be accidentally mixed.

| `ledger` | Unit | Scale | Contents |
|---|---|---|---|
| `484` (MXN, ISO 4217) | cents | integer | Cash: collections, suspense, fees, tax withheld, payouts |
| one id per fund | fund units | integer at 1e-8 | Unit issuance and participant holdings |

Main accounts: on the cash ledger, employer collections (received, not yet allocated), allotment suspense (allocated, units not yet issued), redemption suspense, fee income, ISR withholding payable, payout clearing and rounding residue; on each unit ledger, units in issue (the contra account), participant holdings (one vested and one unvested account per participant per fund) and unit rounding. See [chart-of-accounts.md](chart-of-accounts.md) for the full specification.

Controls that fall out of the structure:

- `Σ participant units = units in issue`, per fund, always.
- Employer collections must net to zero once every batch is allotted. A non-zero balance *is* the list of unresolved contributions.
- Cash held equals the cash the custodian reports, and units held at the provider equal the units allocated to participants; daily reconciliation against the provider's statement is the most important control in an omnibus structure.

Postgres holds history (transaction feed, statements, tax records, analytics), not balances. If Postgres is down, balances still work.

## The contribution lifecycle

```
T    Employer remits MXN (one wire) and a contribution file (per-employee splits)
       ├─ cash lands in employer collections         ← real money, real ledger entry
       └─ file parsed, validated, per-participant amounts staged
       ⚠ CONTROL GATE: file total must equal cash received, to the cent.
         On mismatch nothing is allotted. This is the most important
         control in the contribution path.
T    Cut-off. Subscriptions and redemptions netted per fund.
T    Plan-rule validation per participant: caps, parity, eligibility.
T+n  NAV struck for valuation date T.
T+n  Units allotted at that NAV.
       cash:  employer collections → allotment suspense → fund cash
       units: units in issue → participant holdings   [value date = T]
T+n  Confirmation to employer and participants.
```

Between remittance and allotment the participant has money but no units. The displayed balance must show both, with pending contributions at face value and clearly labelled; a single number that hides this causes disputes about which NAV applied. This window is why suspense accounts and two-phase handling are structural.

**Booking date versus value date.** Batch postings belong to an earlier business day than the day they post. Every transfer carries an explicit `value_date`, defaulting to the booking date for ordinary movements; balances and statements resolve on value date, audit ordering and cursors on booking date.

**Employee and employer contributions are separate lots** from the moment they arrive, because their tax treatment and vesting differ and merging destroys information that cannot be recovered. Each contribution's cash is floored to a whole number of unit ticks; the sub-tick remainder is never issued and is folded into the fund at the next strike (see [rounding-policy.md](rounding-policy.md)).

## Plan rules and tax

Plan-rule evaluation is the highest-risk component, higher than the ledger. A breach does not produce a complaint; it can retroactively destroy the tax treatment of the entire plan, making the employer lose its deduction and every employee's contributions taxable. A bug in the cap engine is a multi-organization tax event.

**Fondo de ahorro** (as commonly documented): employer contributions are deductible only when the benefit is general to all workers, the employer's contribution equals the worker's, and it does not exceed the lower of 13% of salary and 1.3 times the annual UMA. The UMA is updated each 1 February. Funds should be paid out once a year or at termination, and not more than twice a year for social-security purposes.

Design consequences:

1. **Caps are enforced at contribution time**, not detected afterwards; an over-cap contribution is rejected to the employer with a specific reason.
2. **UMA, INPC/UDI, withholding rates and caps are effective-dated reference data**, never code or environment configuration. A stale UMA silently mis-caps everything.
3. **Withdrawal count per participant per fiscal year is a hard gate**, with the plan's own limit configurable below the legal one.
4. **Every rule evaluation stores its inputs, the rule version, the reference values used and the outcome**, so compliance can be proven for any participant and any year from stored data rather than by rerunning current code.
5. **Parity is validated per participant**, not per batch: a batch that balances in aggregate can still breach parity for one employee.

**Interest withholding** (ISR) on savings products is a provisional payment computed on the *capital*, proportional to balance and time held, credited against the taxpayer's annual return, where the definitive calculation is on *real* interest. The annual rate is set by Congress and has changed (0.90% for 2026 under the LIF as documented, up from 0.50%). Consequences: withholding is a daily calculation needing a closing balance for every participant every day; exemptions (for example small savings amounts) are part of the engine; and an annual *constancia* is due by 15 February, a hard deadline to build as a deliverable rather than a report. Whether the platform itself must withhold depends on its legal form.

**Vehicles differ on tax character, permanence and early-withdrawal treatment**, which is why the vehicle type is a first-class dimension driving a versioned rulebook:

| Vehicle | Risk profile | Notes |
|---|---|---|
| Fondo de ahorro | Typically conservative | Exempt within the caps above; withdrawal once a year or at termination |
| Caja de ahorro | Conservative | Per *reglamento* |
| PPR (Art. 151) | Participant-chosen, possibly equity-heavy | Deductible up to a cap; permanence to 65; early withdrawal makes funds and returns accumulable income |
| PPR (Art. 93) | As above | Early withdrawal can trigger withholding on real interest |
| Private pension plan | Long horizon | Own regime |
| Voluntary contributions (AFORE) | CONSAR-regulated | Own regime |

Early-withdrawal penalties depend on age, plan vintage and *real* interest, so a withdrawal quote must be a full calculation with a breakdown, not a number.

## Fees

Three mechanisms, deliberately separate:

| Level | Example | Mechanism | Paid by |
|---|---|---|---|
| Fund | Management fee | Accrued daily **inside the NAV**; no per-participant posting exists | Participants, pro rata, invisibly |
| Participant | Withdrawal fee, extra statement | Cash charge or unit redemption, posted per participant | Participant (or employer, per plan) |
| Organization | Administration fee by bracket | Invoiced to the employer, not deducted from participants | Employer |

Fund-level fees never produce a participant posting; posting one again would double-charge. An organization's fee bracket (by headcount, assets or contribution volume) is **effective-dated and changes over time**: if an organization moves up a bracket in July, June must still bill at the old one, forever.

## What a participant is owed

"Balance and gains" is at least six numbers, and conflating any two produces a complaint that cannot be answered:

| # | Number | Definition |
|---|---|---|
| 1 | Current value | `units × NAV(today)` |
| 2 | Cost basis | The units held, at the NAV each lot was issued at |
| 3 | Unrealised gain or loss | `value − cost basis` |
| 4 | Realised gain or loss | On redeemed units: proceeds minus the cost of those units |
| 5 | Period return | Money-weighted for a participant, time-weighted for a fund |
| 6 | Real return | Nominal return adjusted for inflation (INPC), shown beside the nominal one |

- **Cost basis method:** average cost by default, but individual lots (date, units, NAV, source) are stored regardless, because lots cannot be derived retroactively from an average.
- **Real versus nominal** matters in Mexico: tax on interest is on real interest, and a 6% return in a 5% inflation year is a 1% real return.
- **Fund returns are time-weighted, participant returns are money-weighted (IRR).** They differ, sometimes substantially, so both are computed and labelled, with the difference explained once in the statement footer. Returns are never annualised from a short period.

### The daily attribution identity

For each participant × fund × day, and reconciling exactly to the cent:

```
value_close = value_open + contributions − withdrawals − fees − tax + market_movement
```

where market movement (the plug, and it must be explainable) is units held × (NAV close − NAV open) plus the movement on units allotted intraday. The components are stored, not just the closing value, so a monthly statement is an aggregation rather than a recomputation.

### Storage shape

With a unitized fund every participant's value changes every valuation day, even with no activity. The split is therefore **quantities versus prices**: units are sparse (they change only on activity) and live in the ledger; NAV is dense (one row per fund per day) and lives in the valuation store; value is derived on demand. A dense daily snapshot is still materialised as an immutable regulatory and tax artifact, derived in the nightly batch.

### What the participant sees

- **Daily view:** current value, contributions to date, nominal and real return, pending contributions shown separately with the NAV date they will receive, units and unit price.
- **Monthly statement as a waterfall:** opening balance, employee contributions, employer contributions, withdrawals, fees, ISR withheld, market movement, closing balance. The waterfall answers the question that generates the most support calls: "I put in 1,800 and it only went up 2,282, where did the rest go?" A statement with only opening and closing balances cannot.

## Gains, losses and risk

Values go down, and the system treats that as a normal case.

- **Every value, gain, return and market-movement field is signed.** No unsigned types, no `CHECK (>= 0)` on a gain.
- **Cost basis can exceed value.** A loss is never clamped to zero, and "0.00" is never displayed where the real number is negative.
- Any code path that treats a negative market movement as an error will fire during the first bad month.
- **Test suites must include falling and volatile NAV series**, including a period where a participant's value is below total contributions. A suite that only exercises rising prices passes on a broken implementation.

Where a participant chooses the fund, suitability applies: a dated risk profile with questionnaire responses retained, a risk classification per fund, a rule preventing or explicitly recording a mismatch, and content-hashed evidence of which prospectus the participant saw. Each fund's investment regime (asset-class limits, issuer concentration, duration) is monitored daily at fund level.

## NAV errors

A NAV is struck, published, and later found wrong: a mispriced security, a late corporate action. Every displayed value and every allotment or redemption at that NAV was wrong. The playbook:

1. A **materiality threshold**, agreed in advance (typically basis points of NAV). Below it, correct forward; above it, restate.
2. **Restate the NAV.** NAV history is append-only and versioned: the wrong NAV is never deleted, a corrected NAV is recorded beside it with a reason.
3. **Quantify** who transacted at the wrong price and by how much, from cold storage rather than production.
4. **Compensate** through the correction path: additional units for those who under-received, and a decision (usually absorbed by the administrator) for those who over-received. Never claw back from participants without explicit authority.
5. **Restate downstream:** statements, tax figures and employer reports already issued.
6. **Keep evidence:** the correction run, four-eyes approval, control totals stated before execution, and communications.

The requirement that must exist from day one: **every unit allotment records which NAV version it used.** Without that link, step 3 is impossible.

## The product model

Fondo de ahorro, caja de ahorro, private retirement and high-yield savings differ on about eight axes. Making those axes explicit, effective-dated configuration turns a new product into a record rather than a code branch:

| Axis | Examples |
|---|---|
| `vehicle_type` | Drives the tax rulebook |
| `valuation` | Source (external NAV, reported balance, declared rate) and frequency |
| `accrual` and `allocation_basis` | `UNITS` or average daily balance |
| `contributions` | Sources (employee, employer, voluntary), parity, caps |
| `vesting` | Immediate or a graded schedule |
| `withdrawals` | Permanence, maximum per year, early-withdrawal treatment |
| `tax` | Character and withholding |
| `fees` | Fund level (in NAV), participant level, organization level (bracket) |
| `risk` | Classification, whether the participant chooses the fund |

A high-yield savings account is simply a fund whose NAV only rises by the daily rate: `NAV(t) = NAV(t−1) × (1 + daily_rate)`. One engine, one balance model, one statement format, one reconciliation. Tax character is carried separately from the internal mechanism, so a savings product's returns are still interest for tax purposes even though they are tracked as units.

## Scale

About 500 organizations averaging 500 employees is roughly 250,000 participants. With bi-monthly contributions that is 12 to 25 million ledger transfers a year, against a ledger capacity in the tens of billions: ledger era rollover is a design note, not machinery. Applying a day's gain or loss to every participant costs **one row**, the new NAV; a per-participant model would cost millions of calculations and postings per period. TigerBeetle is chosen for **correctness** (strict serializability, native balance invariants), not for throughput the workload needs. The architecture is justified by correctness and evidence requirements, not by load, and it needs a team that can run it.

## Scope

**v1 ships two vehicles:** *caja de ahorro* and an employer-sponsored retirement fund with vesting (DESIGN-DECISIONS, ADR-0004 and ADR-0012). Contributions arrive by payroll file; cash receipts are posted from a manual reconciliation entry; valuation is a scripted NAV series behind `ValuationSource`; vesting is configurable per organization.

**Declared but not implemented in v1:** tax withholding engine, withdrawals and redemptions, PPR and other vehicles, NAV-error compensation runs, participant suitability, analytics lakehouse with type-2 dimensions, employer-facing reporting beyond the organization summary.

**Out of scope:** managing the investment portfolio, Open Banking standards, card or clearing-house connectivity, multi-currency, and (for now) screening, bureau and device intelligence. Anti-money-laundering obligations (PLD / LFPIORPI) apply to the entity and remain an undesigned placeholder.

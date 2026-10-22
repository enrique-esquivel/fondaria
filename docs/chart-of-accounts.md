# Chart of Accounts — Ledger Domain

Defines every TigerBeetle account and transfer code used by the fund ledger. Numeric
codes are the single source of truth in `libs/ledger-client/.../LedgerCodes.java`; this
document must stay in sync with that enum.

Background: [docs/DOMAIN.md](docs/DOMAIN.md) §4–5,
[`ADR-0004`](docs/DESIGN-DECISIONS.md (ADR-0004)).

## 1. Two ledgers, structurally separated

| `ledger` | Unit | Scale | Contents |
|---|---|---|---|
| `484` (MXN, ISO 4217 numeric) | cents | integer | Cash |
| `1001, 1002, …` (one per fund) | fund units | integer at 1e-8 | Unit issuance and participant holdings |

TigerBeetle forbids cross-ledger transfers, so pesos and units can never be mixed by
accident, and units of one fund can never move to another fund without an explicit
[`FUND_SWITCH`](#4-transfer-codes) (sell in fund A, buy in fund B).

## 2. Cash accounts (`ledger = 484`)

| Account | Code | Normal balance | Who holds one |
|---|---|---|---|
| `BANK_CLEARING` | 1 | debit | One, platform-level. Mirrors the single bank account into which every employer wires its remittance. |
| `EMPLOYER_COLLECTIONS` | 2 | debit | One per client organization. Holds a specific employer's reconciled remittance, staged until allotment. |
| `ALLOTMENT_SUSPENSE` | 3 | debit | One per fund. Cash allocated to participants at cutoff, awaiting unit issuance at the NAV strike. |
| `REDEMPTION_SUSPENSE` | 4 | debit | One per fund. Cash reserved for a participant withdrawal, awaiting payout. |
| `FUND_CASH` | 5 | debit | One per fund. The cash actually backing the fund's NAV. |
| `FEE_INCOME` | 6 | credit | One, platform-level. |
| `ISR_WITHHOLDING_PAYABLE` | 7 | credit | One, platform-level. Tax withheld from redemptions, awaiting remittance to SAT. |
| `PAYOUT_CLEARING` | 8 | debit | One, platform-level. Cash in transit to a participant's bank account. |
| `ROUNDING_RESIDUE` | 9 | either | One, platform-level. Absorbs cent rounding differences. |
| `OPENING_BALANCE_EQUITY` | 10 | credit | One per fund. Counterparty for seeding `FUND_CASH` when onboarding a fund's existing book of business — never used by a live contribution. |

## 3. Unit accounts (`ledger = 1001+`, one set per fund)

| Account | Code | Flags | Who holds one |
|---|---|---|---|
| `UNITS_IN_ISSUE` | 101 | none (contra account) | One per fund. Debited on issuance, credited on redemption/switch-out; by construction its balance is always the negative of the sum of every participant's holdings. |
| `PARTICIPANT_HOLDINGS_VESTED` | 102 | `debits_must_not_exceed_credits` | One per participant per fund. Employee-sourced lots land here immediately (no vesting applies to a participant's own money); employer-sourced lots arrive here once vested. Redeemable. |
| `PARTICIPANT_HOLDINGS_UNVESTED` | 103 | `debits_must_not_exceed_credits` | One per participant per fund. Employer-sourced lots not yet vested. Counted in the participant's position and earns returns from issuance, but is **not** redeemable — see [ADR-0004](docs/DESIGN-DECISIONS.md (ADR-0004)). |
| `UNIT_ROUNDING` | 104 | none | One per fund. Absorbs 1e-8-scale rounding differences. |

## 4. Transfer codes

| Code | Numeric | Legs |
|---|---|---|
| `CASH_RECEIPT` | 1 | cash: `BANK_CLEARING` → `EMPLOYER_COLLECTIONS` (per employer), once a wire is reconciled to a specific employer's remittance. Independent of the dispersion CSV, which only determines the per-employee split within that remittance. |
| `CONTRIBUTION_ALLOTMENT` | 2 | Linked chain, fired atomically at the NAV strike for valuation date T: cash `EMPLOYER_COLLECTIONS` → `ALLOTMENT_SUSPENSE` → `FUND_CASH`; units `UNITS_IN_ISSUE` → `PARTICIPANT_HOLDINGS_VESTED` (employee lots, and employer lots under an immediate-vesting plan) and/or → `PARTICIPANT_HOLDINGS_UNVESTED` (employer lots under a scheduled-vesting plan). |
| `VESTING` | 3 | units: `PARTICIPANT_HOLDINGS_UNVESTED` → `PARTICIPANT_HOLDINGS_VESTED`. Produced by the vesting schedule job, never a flag flip. |
| `REDEMPTION` | 4 | units: `PARTICIPANT_HOLDINGS_VESTED` → `UNITS_IN_ISSUE` (vested only); cash: `FUND_CASH` → `REDEMPTION_SUSPENSE` → `PAYOUT_CLEARING`, net of any `FEE_CHARGE`/`TAX_WITHHOLDING` legs. |
| `FUND_SWITCH` | 5 | units: `PARTICIPANT_HOLDINGS_VESTED` (fund A) → `UNITS_IN_ISSUE` (A), linked with `UNITS_IN_ISSUE` (B) → `PARTICIPANT_HOLDINGS_VESTED` (B); cash: matching `FUND_CASH` (A) → `FUND_CASH` (B) at both funds' NAV. |
| `FEE_CHARGE` | 6 | cash: `FUND_CASH` → `FEE_INCOME`. |
| `TAX_WITHHOLDING` | 7 | cash: (redemption leg) → `ISR_WITHHOLDING_PAYABLE`, before `PAYOUT_CLEARING`. |
| `CORRECTION` | 8 | References the original `transfer_id`; reversing/adjusting legs mirroring the original movement. |
| `NAV_ERROR_COMPENSATION` | 9 | Units or cash correction, tagged with the corrected NAV version. |
| `OPENING_BALANCE` | 10 | Migration only, never a live contribution. Linked chain: cash `OPENING_BALANCE_EQUITY` → `FUND_CASH`; units `UNITS_IN_ISSUE` → `PARTICIPANT_HOLDINGS_VESTED` and/or → `PARTICIPANT_HOLDINGS_UNVESTED`, preserving whatever vested/unvested split existed in the legacy system being migrated. |

## 5. Which accounts may go negative

Only `PARTICIPANT_HOLDINGS_VESTED` and `PARTICIPANT_HOLDINGS_UNVESTED` carry a hard
ledger restriction (`debits_must_not_exceed_credits`) — a participant can never hold
negative units, vested or unvested, enforced by TigerBeetle itself rather than
application code.

Every other account is unrestricted at the ledger level. `UNITS_IN_ISSUE` is a contra
account and is expected to carry a negative balance in the conventional sense — by
construction, `Σ participant units (vested + unvested) = -1 × UNITS_IN_ISSUE balance`,
per fund, always. Suspense and clearing accounts (`ALLOTMENT_SUSPENSE`,
`REDEMPTION_SUSPENSE`, `BANK_CLEARING`, `PAYOUT_CLEARING`) are transiently unbalanced
by design and must net to zero once their in-flight batch settles — a non-zero balance
*is* the list of unresolved items. `FUND_CASH` and `FEE_INCOME` should never go
negative in practice, but that is an application-level invariant (never authorize a
`REDEMPTION` or `FEE_CHARGE` leg exceeding the available balance), not a ledger flag.

## 6. Worked example (a) — employer + employee contribution

Acme Corp (employer) wires **MXN 7,500.00** to the fund's bank account and provides a
dispersion CSV for two participants, under a plan with a scheduled (non-immediate)
employer vesting policy. Fund `CAJA_1` (ledger `1001`) strikes NAV at **MXN 10.00000000**
per unit for valuation date T.

| Participant | Employee contribution | Employer contribution |
|---|---|---|
| P-001 | MXN 3,000.00 | MXN 1,500.00 |
| P-002 | MXN 2,000.00 | MXN 1,000.00 |
| **Total** | **MXN 5,000.00** | **MXN 2,500.00** |

**Step 1 — `CASH_RECEIPT`** (wire reconciled to Acme Corp):

```
BANK_CLEARING          -750,000 cents
EMPLOYER_COLLECTIONS[Acme]  +750,000 cents
```

**Step 2 — cutoff and plan-rule validation.** No ledger entry; staging only (Phase 3).

**Step 3 — `CONTRIBUTION_ALLOTMENT`** at the NAV strike (T+n), one linked chain:

```
cash:
  EMPLOYER_COLLECTIONS[Acme]     -750,000 cents
  ALLOTMENT_SUSPENSE[CAJA_1]     +750,000 / -750,000 cents (pass-through)
  FUND_CASH[CAJA_1]              +750,000 cents

units (amount ÷ NAV 10.00000000):
  UNITS_IN_ISSUE[CAJA_1]                          -750.00000000 units
  PARTICIPANT_HOLDINGS_VESTED[P-001, CAJA_1]      +300.00000000 units   (employee lot)
  PARTICIPANT_HOLDINGS_VESTED[P-002, CAJA_1]      +200.00000000 units   (employee lot)
  PARTICIPANT_HOLDINGS_UNVESTED[P-001, CAJA_1]    +150.00000000 units   (employer lot, scheduled vesting)
  PARTICIPANT_HOLDINGS_UNVESTED[P-002, CAJA_1]    +100.00000000 units   (employer lot, scheduled vesting)
```

Balances to zero: `EMPLOYER_COLLECTIONS[Acme]` returns to 0 (the control that proves the
batch fully allotted); `ALLOTMENT_SUSPENSE[CAJA_1]` nets to 0; `UNITS_IN_ISSUE[CAJA_1]`
moves by exactly `-(300+200+150+100) = -750.00000000`, matching
`Σ participant units = 750.00000000 = 7,500.00 MXN ÷ 10.00000000 NAV`.

## 7. Worked example (b) — vesting event

One year later, Acme Corp's plan fully vests P-001's employer lot (a single-tranche
example; a graded schedule repeats this transfer per tranche).

**`VESTING`**:

```
PARTICIPANT_HOLDINGS_UNVESTED[P-001, CAJA_1]   -150.00000000 units
PARTICIPANT_HOLDINGS_VESTED[P-001, CAJA_1]     +150.00000000 units
```

P-001 held these units and earned fund returns on them since the original
`CONTRIBUTION_ALLOTMENT` — vesting changes entitlement to redeem, not ownership or the
issuance date. After this transfer: `PARTICIPANT_HOLDINGS_VESTED[P-001, CAJA_1]` =
300.00000000 (employee) + 150.00000000 (now-vested employer) = 450.00000000;
`PARTICIPANT_HOLDINGS_UNVESTED[P-001, CAJA_1]` = 0.

# Rounding policy — unit issuance

**Status:** Task 21.3. Extends `phase-21-allotment.md`'s `CONTRIBUTION_ALLOTMENT` chain (Task
21.2) and `chart-of-accounts.md` §3–4. Referenced from the participant statement footer
(docs/DOMAIN.md) so the treatment is disclosed, not merely internal.

---

## 1. The rule

`AllotmentService` divides each participant-source contribution by the struck NAV to find how
many units it buys: `units = amountMinorUnits × 1e14 ÷ navScaled`. Integer division leaves a
remainder on nearly every row — the cash rarely buys a whole number of unit ticks (1e-8 of a
unit) exactly.

> **Units issued are floored to the unit scale (1e-8).** The participant receives the largest
> whole number of ticks their cash buys; the sub-tick remainder is **not** issued. All of the
> cash still enters `FUND_CASH`. Rounding is therefore **always in the fund's favour**, never in
> a participant's, and never discarded.

## 2. Who benefits, and how

**The fund's gain is not a posting — it is the absence of one.** The full contribution amount
enters `FUND_CASH` through the batch's ordinary cash legs; the unit side issues marginally fewer
units than the cash's exact value would buy. At the next NAV strike, the same pool of cash is
divided by a slightly smaller number of units in issue, so NAV per unit ticks up by an
infinitesimal amount and **every existing holder benefits proportionally** — the same
mechanism `docs/ENGINEERING-RULES.md` §2 describes for every other gain or loss in this system: one number
changes, and every participant's value moves correctly. There is no per-participant
computation, no special "rounding participant," and no separate transfer.

**`UNIT_ROUNDING` (chart-of-accounts.md §3, code 104, unit ledger) is not posted to by this
task**, and the residue is never routed through `ROUNDING_RESIDUE` (code 9, cash ledger) either
— that account absorbs cent-scale cash differences elsewhere in the platform and is untouched
here. `UNITS_IN_ISSUE` is debited by exactly the sum credited to participants, nothing more.
Were a future task ever to post the residue explicitly, it would be a *unit*-scale movement and
would belong on `UNIT_ROUNDING`, never `ROUNDING_RESIDUE` — but v1 does not do this, by design:
see §2's mechanism above for why a posting is unnecessary.

## 3. Because it is not posted, it is recorded

An auditor cannot see a rounding gain that exists only as an absent posting. Every allotment run
(one `AllotmentService#allot` call, i.e. one staged batch) writes one row to
`workflow.allotment_residual` (`V17`), append-only:

| Column | Meaning |
|---|---|
| `batch_id` | The run this residual belongs to (primary key — idempotent on rerun) |
| `fund_id`, `nav_version_id` | Which fund and struck price this run used |
| `row_count` | The number of participant-source contribution legs priced this run |
| `residual_minor_units` | MXN cents: cash allotted minus the value of units issued |

`residual_minor_units = cash allotted − Money.value(units issued, NAV)`, computed once over the
run's totals. Because `Money.value` performs the exact inverse of the division that floors units,
this identity holds exactly, every run:

```
Σ (units issued × NAV) + residual == cash allotted
```

## 4. The bound

**Residual is always ≥ 0.** Flooring can only ever leave cash on the table — it never issues more
units than the cash actually bought — so a negative residual means units were over-issued
somewhere upstream, and `AllotmentService` fails the run rather than recording it.

**The bound is one sub-tick fraction of a unit's value per allotted row.** Each floor operation
discards a raw remainder strictly less than the NAV's own scaled value, so a run's residual can
never exceed `⌈row_count × navScaled ÷ 1e14⌉` MXN cents. A run exceeding this ceiling **fails**;
it is not absorbed — at that point the arithmetic is wrong, not the residue.

**Worked arithmetic.** At 200,000 rows and a NAV near MXN 10.00 (`nav_scaled` = 1,000,000,000):

```
ceiling = ⌈row_count × navScaled ÷ 1e14⌉
        = ⌈200,000 × 1,000,000,000 ÷ 100,000,000,000,000⌉
        = ⌈200,000,000,000,000 ÷ 100,000,000,000,000⌉
        = ⌈2⌉ = 2 centavos
```

Any materially larger figure than a few centavos, at that scale, means the arithmetic — not the
residue — is broken.

## 5. Disclosure

This policy is referenced from the participant statement footer
(docs/DOMAIN.md's monthly waterfall) once that view is built (Phase 24):
the waterfall's market-movement line already carries every fund-level price change, including the
infinitesimal one this rounding rule produces at the next strike, and the footer names this
document as the source of the rule rather than leaving the mechanism undocumented for a
participant who asks where a fraction of a centavo went.

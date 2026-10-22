# Contribution File — Format v2

The dispersion file an employer (or its payroll system) produces for one
payroll period. It is uploaded to object storage and submitted to
`contracts/openapi/contribution-api.yaml` by reference — see `ADR-0009`.

**This document supersedes `contribution-file-v1.md`.** The platform accepts
v2 and rejects a v1 file. Nothing was ever live on v1, so no migration path is
owed. §6 records what changed and why.

Background: `AGENTS.md` §2 (units, not pesos), `docs/chart-of-accounts.md`
§6 (worked example this document reuses), `ADR-0004` (employee/employer
contribution lots), `ADR-0009` (transport).

## 1. What the file is

**A plain CSV. One column-header row, then one row per participant.** No
record types, no metadata, no trailer — a rectangle, the shape a payroll
system exports and an HR spreadsheet already has.

- UTF-8, no byte-order mark.
- `\n` line endings.
- Comma-delimited, RFC 4180 quoting for any field containing a comma or
  quote (none of the fields defined below need it, but a producer must still
  quote correctly if it ever passes through free text).
- Line 1 is the column-header row (§2). Every line after it is a participant
  row (§3), all of the same shape.

### 1.1 The batch's own declarations are not in the file

Which plan, which payroll period, which value date, how much in total, how
many rows — none of that is in the file. It is declared in the submission
call (`contracts/openapi/contribution-api.yaml`), because it describes the
*batch*, not the participants, and a file is a poor place to put a value that
has to be typed, validated and rejected on its own terms.

This **reverses `ADR-0009`'s** "the file is the single declared source of
truth for the batch's content; the API request only carries transport
metadata". The declarations are still declared exactly once, and still by the
employer — they simply arrive as a typed request body rather than as a
positional row in a CSV. `ADR-0009` needs amending to say so.

**The object key keeps the file identifiable on its own.** The service mints
it from the declared values:

```
contribution-batches/{organizationId}/{planId}/{payrollPeriod}/{uuid}-{fileName}
```

so an object pulled out of storage years later still says which employer,
plan and period it belongs to. The key **mirrors** the declaration and is
never the authority for it — a path segment is untyped, case-fragile and
length-limited, and no control should rest on one. Submission requires the
key to sit under the prefix its declared plan and period generate, so the two
cannot drift apart.

### 1.2 Why the sources are columns

v1 keyed amounts by source — one row per participant *per source* — so that a
new contribution source would need no new column. That flexibility was
expensive: participant attributes cannot live on a row that repeats per
source, so they need a second record type, which needs a join, orphan checks
in both directions, an ordering rule and an ambiguous row count.

The vocabulary is `EMPLOYEE`, `EMPLOYER`, `VOLUNTARY`, and it is a subset of
that set for **every** vehicle the platform plans to carry:
docs/DOMAIN.md declares `[EMPLOYEE, EMPLOYER, VOLUNTARY]`
for the retirement product and `[EMPLOYEE]` for high-yield savings; fondo de
ahorro is employee-and-employer by construction, since `LISR` art. 27 fr. XI
ties the employer contribution to the employee's. The vocabulary is rooted in
how each bucket is treated for tax, so it moves on the timescale of tax
reform, not of product launches.

**The cost of a new source is therefore bounded and known:** one constant in
`ContributionSource`, one entry in the parser's column order, one more column
here, and a new format version. That is cheaper, once, than a second record
type on every file forever.

Whether a plan *permits* a source is a plan-rule check, not a format check —
Task 3.5. A caja de ahorro file simply carries `0` in `voluntaryCents`.

## 2. Column-header row

Line 1, exactly these seven names, in this order:

```
participantInternalRef,participantPayrollRef,employmentStartDate,enrolmentDate,employeeCents,employerCents,voluntaryCents
```

A file whose first line is not exactly this is `MALFORMED_FILE`. The row is
required rather than optional because it is what makes the file readable by
a person and by every spreadsheet and data tool without a spec in hand, and
because a producer that silently shifts a column would otherwise be read as
valid — an employer amount landing in the voluntary column is a silent
misallocation, not a parse error.

## 3. Participant row

Every line after the header. One per participant, and the only data shape in
the file.

| Column | Type | Notes |
|---|---|---|
| `participantInternalRef` | string | The platform's stable internal participant reference. Never re-used across participants, never reassigned. No two rows may share it (`DUPLICATE_ROW`, §4). |
| `participantPayrollRef` | string | The payroll system's own reference for this employee. Carried alongside the internal ref so a re-mapping (payroll system migration, ref correction) never requires re-keying history — required by Task 3.1. Unique within an organization, never globally: two employers may legitimately use the same payroll reference. |
| `employmentStartDate` | date, `YYYY-MM-DD` | When the employee joined the **organization**. This is the seniority clock, and it is what a graded vesting schedule runs off (`ADR-0004`). |
| `enrolmentDate` | date, `YYYY-MM-DD` | When the employee joined the **plan**. Must not be earlier than `employmentStartDate` (`MALFORMED_FILE`, §4). |
| `employeeCents` | integer | This participant's employee deduction this period. `0` if none. |
| `employerCents` | integer | This participant's employer contribution this period. `0` if none. This is the money a vesting schedule governs. |
| `voluntaryCents` | integer | This participant's voluntary contribution this period. `0` if none, which is every caja de ahorro row. |

Every amount is a non-negative integer of MXN cents: no decimal point, no
thousands separator, no currency symbol. Amounts are always MXN — the file
carries no currency column because the platform is MXN-only (`AGENTS.md` §1).

A row whose three amounts are all `0` is malformed: a participant
contributing nothing this period simply has no row. A single zero column is
not an error — it is how the format says "nothing from this source", and it
is why the columns exist.

**Enrolment.** This is the row Task 2b.3 enrols from: a participant the
platform has not seen before is created from it, and one it has seen is
matched on `participantInternalRef`.

**The two dates are routinely different and must never be collapsed into
one.** An employee may work for years before the fund is offered to them.
Under a service-based vesting schedule the employment start can make them
fully vested on their first contribution while the enrolment date would not,
so conflating them misallocates employer money at withdrawal — in the
participant's favour or against it, silently. `phase-02b` Task 2b.3 states
this as the case worth an explicit test. Both dates sit on the same row as
the `employerCents` they govern, so a vesting computation reads one row and
joins nothing.

No name, `RFC` or `CURP`. Participants are pseudonymous in v1 of the
platform; identifying data waits for Phase 16 and its crypto-shredding
prerequisite (Phase 13.5).

## 4. Rejection contract

**Whole-batch rejection** — the file or its declarations are wrong; no row is
staged, no partial allotment ever occurs (`AGENTS.md` §1's "never a partial
allotment" via Task 3.4). Checked in this order, cheapest first, before any
plan-rule evaluation:

| Order | Check | Error code |
|---|---|---|
| 1 | The uploaded object's SHA-256 matches the `checksumSha256` submitted with the batch | `CHECKSUM_MISMATCH` |
| 2 | The file parses: line 1 is exactly the column-header row of §2, every line after it is a well-formed participant row, no row with three zero amounts, and no row whose `employmentStartDate` is later than its `enrolmentDate` | `MALFORMED_FILE` |
| 3 | The submitted `declaredRowCount` equals the actual number of participant rows | `ROW_COUNT_MISMATCH` |
| 4 | No two participant rows share a `participantInternalRef` | `DUPLICATE_ROW` |
| 5 | For each source, the submitted declared total for that source equals the sum of that column | `SOURCE_SUBTOTAL_MISMATCH` |
| 6 | The three declared source totals sum to the submitted `declaredTotalCents` | `DECLARED_TOTAL_MISMATCH` |

Checks 3 to 6 compare the file against **what the submission declared**.
That is the whole point of separating them: the employer states what it
believes it is sending, and the file either bears that out or it does not.

The last two are the controls the phase doc calls out as mattering most,
split so the reporting stays source-aware: `SOURCE_SUBTOTAL_MISMATCH`
localizes the discrepancy to one source (Task 3.1 requires employee and
employer amounts stay separately represented end to end, including in how a
mismatch is reported), while `DECLARED_TOTAL_MISMATCH` is the grand total
that Task 3.4's gate reconciles against the money actually received.

**The order is the contract, not an optimisation.** A truncated file is
caught by the row count before its columns are summed, so the employer is
told "rows are missing" rather than "your employer total is short" — the same
underlying fault, but only one of those two answers leads anywhere.

**There is no `ORGANIZATION_MISMATCH`.** v1 had one because the file declared
an organization that could disagree with the URL path. The file declares
nothing now, and a submitted object key must already sit under its own
organization's prefix — enforced at submission, with a `400`, before anything
is fetched.

**Per-row rejection** — a specific participant's row breaches a plan rule
(eligibility, permitted source, parity, a cap). This document only defines
the row shape those rules evaluate; the rules themselves, their evaluation
order, and whether a breach rejects one row or the whole batch (a
per-plan-configured policy) are Task 3.5's contract, not this one. A non-zero
amount in a column the plan does not permit is one of those rules, not a
format error.

**Nothing in a rejection message or its details names a participant.** A
rejection body is logged by everything it passes through, and
`participantInternalRef`, `participantPayrollRef` and the employment dates
all identify a person (`AGENTS.md` §7).

## 5. Worked example

Reusing `docs/chart-of-accounts.md` §6's scenario: Acme Corp wires
MXN 7,500.00 for payroll period `2026-08-B`, value date 2026-08-20, plan
`PLAN-ACME-CAJA-1`, under a scheduled (non-immediate) employer vesting
policy, for two participants.

The file:

```
participantInternalRef,participantPayrollRef,employmentStartDate,enrolmentDate,employeeCents,employerCents,voluntaryCents
P-001,ACME-EMP-0142,2019-03-01,2026-01-15,300000,150000,0
P-002,ACME-EMP-0891,2026-02-01,2026-02-01,200000,100000,0
```

The submission that declares it:

```json
{
  "objectKey": "contribution-batches/{organizationId}/PLAN-ACME-CAJA-1/2026-08-B/{uuid}-acme-2026-08-b.csv",
  "checksumSha256": "…",
  "planId": "PLAN-ACME-CAJA-1",
  "payrollPeriod": "2026-08-B",
  "valueDate": "2026-08-20",
  "declaredTotalCents": 750000,
  "declaredRowCount": 2,
  "declaredSourceCents": { "EMPLOYEE": 500000, "EMPLOYER": 250000, "VOLUNTARY": 0 }
}
```

The `employeeCents` column (300000 + 200000) equals the declared 500000; the
`employerCents` column (150000 + 100000) equals the declared 250000;
`voluntaryCents` is zero throughout and declared as `0`; the three declared
totals sum to `declaredTotalCents` (750000); `declaredRowCount` (2) matches
two participant rows. That 750000 cents (MXN 7,500.00) is what `CASH_RECEIPT`
posts to `EMPLOYER_COLLECTIONS[Acme]` in the ledger worked example this
reuses — the grand total is what the reconciliation gate (Task 3.4) checks
against that cash, and the per-source totals are what
`CONTRIBUTION_ALLOTMENT` later splits into vested/unvested unit lots per
source (Task 3.6 stops at staging; allotment is Phase 21).

The two participants are chosen to make §3's point concrete. P-001 joined
Acme in 2019 and the plan in 2026 — seven years of service before their first
peso goes in — while P-002 joined both on the same day. Under a service-based
vesting schedule these two are in entirely different places on their first
contribution, and the file is the only thing that knows it.

## 6. What changed from v1, and why

| Change | Reason |
|---|---|
| **The file is a plain CSV rectangle.** The `H`, `S` and `D` record types are gone | One shape, readable by every tool without a spec in hand. v1's three record types existed to key amounts by source and to carry batch metadata; neither reason survives. |
| **One row per participant, one column per source** | §1.2. The source vocabulary is closed enough that keying by source bought flexibility the platform will rarely use, at the cost of a second record type, a join, two orphan checks, an ordering rule and an ambiguous row count. |
| **Batch declarations moved out of the file into the submission call** | §1.1. They describe the batch, not the participants, and they need typing, validation and rejection on their own terms. Reverses `ADR-0009`; the object key now carries plan and period so the artifact stays identifiable. |
| `employmentStartDate` and `enrolmentDate` are new | Task 2b.3 auto-enrols participants from the file and rejects enrolment without an employment start date. v1 carried no dates, so auto-enrolment could not work at all. |
| `participantPayrollRef` is on the participant row | It is a property of the participant, not of a contribution. On v1's source-keyed row it was duplicated per source and could disagree between copies. |
| `ORGANIZATION_MISMATCH` is gone | §4. Nothing in the file declares an organization any more, and the object-key prefix check already refuses another employer's key at submission. |
| A row with three zero amounts is malformed; a single zero column is not | v1's rule was "a source with nothing to contribute has no row". The equivalent here is "a participant contributing nothing has no row", and a zero column is how the format says nothing from that source. |

No amount changed type, and the reconciliation gate is untouched: it compares
a declared total against money actually received, and it never read rows.

# Contribution File — Format v1

> **Superseded by `contribution-file-v2.md`. Do not implement this document.**
>
> The platform accepts `fileFormatVersion` `2` and rejects `1` as
> `MALFORMED_FILE`. v2 is one header line followed by a plain rectangle: one
> row per participant, one column per contribution source, carrying the
> employment dates Task 2b.3 enrols from. The `S` and `D` records below no
> longer exist; v2 §7 lists every difference and §1.1 explains why the
> sources became columns. Nothing was ever live on v1, so no migration path
> is owed and none is offered.
>
> This file is kept, unchanged below the banner, because `ADR-0009` and the
> phase documents cite it by name and describe what those decisions said at
> the time. It is a historical record, not a specification anything reads.

The dispersion file an employer (or its payroll system) produces for one
payroll period. It is uploaded to object storage and submitted to
`contracts/openapi/contribution-api.yaml` by reference — see `ADR-0009`.
The file is the single declared source of truth for the batch's content;
the API request only carries transport metadata (object key, checksum).

Background: `AGENTS.md` §2 (units, not pesos), `docs/chart-of-accounts.md`
§6 (worked example this document reuses), `ADR-0004` (employee/employer
contribution lots), `ADR-0009` (transport).

## 1. Encoding and framing

- UTF-8, no byte-order mark.
- `\n` line endings.
- Comma-delimited, RFC 4180 quoting for any field containing a comma or
  quote (none of the fields defined below need it, but a producer must
  still quote correctly if it ever passes through free text).
- Exactly one header record, then zero or more source-subtotal records,
  then one or more detail records. No trailer.
- The first column of every record is `recordType` (`H`, `S`, or `D`); a
  parser switches on it rather than on line number, so the three record
  shapes coexist in one file.
- Every amount is source-keyed by an `S` or `D` record — there is no fixed
  per-source column anywhere in the format. A plan that permits a source
  this document has never seen (docs/DOMAIN.md's
  `sources` list may grow) needs no format change: one more `source` enum
  value, no new column.
- File format version `1` is asserted by the header's `fileFormatVersion`
  column, not by the filename, so the object key is free-form.

## 2. Header record (`recordType = H`)

Exactly one, first line of the file.

| Column | Type | Notes |
|---|---|---|
| `recordType` | `"H"` | Literal. |
| `fileFormatVersion` | integer | `1` for this document. |
| `organizationId` | string | Must equal the `organizationId` in the API submission's URL path. Mismatch is a whole-batch rejection (`ORGANIZATION_MISMATCH`) — see §5. |
| `planId` | string | The plan (reglamento) this batch is contributing under. |
| `payrollPeriod` | string | The employer's payroll period identifier, e.g. `2026-08-B` for the second bi-monthly period of August. Opaque to the platform; carried through for the employer's own reconciliation. |
| `valueDate` | date, `YYYY-MM-DD` | The date this batch is staged for — Task 3.6. Never the date the file was produced or uploaded. |
| `declaredTotalCents` | integer | Declared grand total across every source. This is the number that must equal the money actually received (Task 3.4's reconciliation gate) — the control the phase doc calls out as mattering most. Must equal the sum of the `S` records' `declaredSourceCents` (see §5). |
| `rowCount` | integer | Total number of detail (`D`) records in the file. A cheap truncation guard, checked before the (more expensive) per-source sums — see §5. |

`declaredTotalCents` and every amount below are non-negative integers, no
decimal point, no thousands separator, no currency symbol. Amounts are
always MXN — the file carries no currency column because the platform is
MXN-only (`AGENTS.md` §1).

## 2a. Source-subtotal record (`recordType = S`)

One per distinct source present in the file's detail records. These keep
employee, employer and voluntary money separately declared end to end —
required by Task 3.1 — without a fixed column per source.

| Column | Type | Notes |
|---|---|---|
| `recordType` | `"S"` | Literal. |
| `source` | enum: `EMPLOYEE`, `EMPLOYER`, `VOLUNTARY` | The source this subtotal covers. No two `S` records may share a source (`DUPLICATE_ROW`). |
| `declaredSourceCents` | integer | Declared sum of every `D` record with this `source`. Checked against the actual summed rows (`SOURCE_SUBTOTAL_MISMATCH`, §5). |

Every source that appears in a `D` record must have exactly one `S` record,
and every `S` record must cover at least one `D` record — a missing or
orphan subtotal is a whole-batch rejection (§5). A source with nothing to
contribute has neither an `S` nor any `D` record; caja de ahorro
(`ADR-0004`, v1's only vehicle) simply never emits a `VOLUNTARY` record.

## 3. Detail record (`recordType = D`)

One per participant per source. A participant with both an employee
deduction and an employer match in the same payroll run appears on two
rows, not one — see §6 for why.

| Column | Type | Notes |
|---|---|---|
| `recordType` | `"D"` | Literal. |
| `participantInternalRef` | string | The platform's stable internal participant reference. Never re-used across participants, never reassigned. |
| `participantPayrollRef` | string | The payroll system's own reference for this employee. Carried alongside the internal ref so a re-mapping (payroll system migration, ref correction) never requires re-keying history — required by Task 3.1. |
| `source` | enum: `EMPLOYEE`, `EMPLOYER`, `VOLUNTARY` | Which contribution source this row funds, matching docs/DOMAIN.md's product-level `sources` vocabulary. Whether a plan *permits* a given source is a plan-rule check, not a file-format check — Task 3.5. |
| `amountCents` | integer | This row's contribution amount, in cents. Non-negative; a zero-amount row is malformed (`MALFORMED_FILE`) — a source with nothing to contribute simply has no row. |

## 4. Checksum

The uploader computes SHA-256 over the exact bytes uploaded to object
storage and passes it, lowercase hex, as `checksumSha256` in the batch
submission call (`contracts/openapi/contribution-api.yaml`). The checksum
is never embedded in the file itself — it is a property of the transport
(did the bytes arrive intact), not of the content, and putting it inside
the file it's supposed to check would be circular. The async validator
recomputes the checksum from the uploaded object and rejects the whole
batch on mismatch before parsing a single row.

## 5. Rejection contract

**Whole-batch rejection** — the file is structurally or numerically wrong;
no row is staged, no partial allotment ever occurs (`AGENTS.md` §1's
"never a partial allotment" via Task 3.4). Checked in this order, cheapest
first, before any plan-rule evaluation:

| Order | Check | Error code |
|---|---|---|
| 1 | Uploaded object's SHA-256 matches the submitted `checksumSha256` | `CHECKSUM_MISMATCH` |
| 2 | File parses: exactly one `H` record first, then `S` records, then `D` records; every record well-formed, no unknown `recordType`, every column present and of the declared type | `MALFORMED_FILE` |
| 3 | Header `organizationId` equals the URL path `organizationId` | `ORGANIZATION_MISMATCH` |
| 4 | Header `rowCount` equals the actual number of `D` records | `ROW_COUNT_MISMATCH` |
| 5 | No two `D` records share `(participantInternalRef, source)`, and no two `S` records share `source` | `DUPLICATE_ROW` |
| 6 | Every source in a `D` record has exactly one `S` record and vice versa, and for each source the sum of that source's `D.amountCents` equals its `S` record's `declaredSourceCents` | `SOURCE_SUBTOTAL_MISMATCH` |
| 7 | The sum of every `S` record's `declaredSourceCents` equals the header's `declaredTotalCents` | `DECLARED_TOTAL_MISMATCH` |

The last two are the controls the phase doc calls out as mattering most,
split so the reporting stays source-aware: `SOURCE_SUBTOTAL_MISMATCH`
localizes the discrepancy to one source (Task 3.1 requires employee and
employer amounts stay separately represented end to end, including in how a
mismatch is reported), while `DECLARED_TOTAL_MISMATCH` is the grand total
that Task 3.4's gate reconciles against the money actually received.

**Per-row rejection** — a specific participant's row breaches a plan rule
(eligibility, permitted source, parity, a cap). This document only defines
the row shape those rules evaluate; the rules themselves, their evaluation
order, and whether a breach rejects one row or the whole batch (a
per-plan-configured policy) are Task 3.5's contract, not this one.

## 6. Worked example

Reusing `docs/chart-of-accounts.md` §6's scenario: Acme Corp wires
MXN 7,500.00 for payroll period `2026-08-B`, value date 2026-08-20, plan
`PLAN-ACME-CAJA-1`, under a scheduled (non-immediate) employer vesting
policy, for two participants:

| Participant | Payroll ref | Employee contribution | Employer contribution |
|---|---|---|---|
| P-001 | ACME-EMP-0142 | MXN 3,000.00 | MXN 1,500.00 |
| P-002 | ACME-EMP-0891 | MXN 2,000.00 | MXN 1,000.00 |
| **Total** | | **MXN 5,000.00** | **MXN 2,500.00** |

```
H,1,ORG-ACME,PLAN-ACME-CAJA-1,2026-08-B,2026-08-20,750000,4
S,EMPLOYEE,500000
S,EMPLOYER,250000
D,P-001,ACME-EMP-0142,EMPLOYEE,300000
D,P-001,ACME-EMP-0142,EMPLOYER,150000
D,P-002,ACME-EMP-0891,EMPLOYEE,200000
D,P-002,ACME-EMP-0891,EMPLOYER,100000
```

The `EMPLOYEE` subtotal (500000) = 300000 + 200000; the `EMPLOYER` subtotal
(250000) = 150000 + 100000; their sum equals the header `declaredTotalCents`
(750000); `rowCount` (4) matches four `D` records. There is no `VOLUNTARY`
record because this batch has none — no zero-filled column. That 750000
cents (MXN 7,500.00) is what `CASH_RECEIPT` posts to
`EMPLOYER_COLLECTIONS[Acme]` in the ledger worked example this reuses — the
grand total is what the reconciliation gate (Task 3.4) checks against that
cash, and the per-source subtotals are what `CONTRIBUTION_ALLOTMENT` later
splits into vested/unvested unit lots per source (Task 3.6 stops at staging;
allotment is Phase 21).

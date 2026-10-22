# Valuation File — Format v1

The file an external investment platform delivers to report what a fund's
pooled assets are worth on a valuation date. It is uploaded to object storage
and submitted to `contracts/openapi/valuation-api.yaml` by reference — the
same transport `ADR-0009` settled for contribution files, reused deliberately
rather than reinvented (`phase-20-fund-accounting-nav.md` Task 20.3).

Background: `docs/ENGINEERING-RULES.md` §2 (units, not pesos), docs/DOMAIN.md
§3 (valuation models), `ADR-0009` (transport), `contracts/files/contribution-file-v2.md`
(the format conventions this document follows).

> **We do not manage the portfolio.** This file carries what somebody else
> reports the assets are worth. Nothing in it is a price we computed, and
> nothing in it is a per-participant number. The NAV per unit is struck from
> it (Task 20.4); it is not in the file.

## 1. What the file is

**A plain CSV. One column-header row, then one row per fund per valuation
date.** The same rectangle `contribution-file-v2.md` §1 settled on, and for
the same reasons: one shape, readable by every tool without a spec in hand.

- UTF-8, no byte-order mark.
- `\n` line endings.
- Comma-delimited, RFC 4180 quoting for any field containing a comma or
  quote.
- Line 1 is the column-header row (§2). Every line after it is a valuation
  row (§3), all of the same shape.

### 1.1 Why the valuation model is not in the file

The platform runs **Model B — unitize a reported balance**
(docs/DOMAIN.md, settled in `BACKLOG.md` § "Road to the
demo"). The provider reports a total pooled balance and the platform strikes
`nav_per_unit = reported value ÷ units in issue` itself, reading units in
issue from the ledger.

So the file carries **the reported balance and nothing derived from it**. A
provider running Model A would report its own NAV per unit instead, and that
is a different file format read by a different adapter on the same
`ValuationSource` port (`libs/valuation`) — not an extra optional column
here. A format that could carry either would need a rule for what happens
when a file carries both, and the answer to that rule is the whole
valuation model.

### 1.2 The batch's declarations are not in the file

Which provider sent it, how many rows it has, its checksum — none of that is
in the file. It is declared in the submission call, exactly as
`contribution-file-v2.md` §1.1 settled for contributions: the declarations
describe the *batch*, not the funds, and they need typing, validation and
rejection on their own terms.

**The object key keeps the file identifiable on its own.** The service mints
it from the declared values:

```
valuations/{source}/{uuid}-{fileName}
```

so an object pulled out of storage years later still says which provider it
came from. The key **mirrors** the declaration and is never the authority for
it. Submission requires the key to sit under the prefix its declared source
generates, so the two cannot drift apart.

## 2. Column-header row

Line 1, exactly these four names, in this order:

```
fundId,valuationDate,reportedValueCents,providerReference
```

A file whose first line is not exactly this is `MALFORMED_FILE`. Required
rather than optional for the reason §2 of the contribution contract gives: a
producer that silently shifts a column would otherwise be read as valid, and
a reported balance landing in the wrong column moves every participant's
balance in that fund.

## 3. Valuation row

Every line after the header. One per fund per valuation date, and the only
data shape in the file.

| Column | Type | Notes |
|---|---|---|
| `fundId` | UUID | A fund registered in `reference.fund` (Task 20.1). Resolved **as of the row's own `valuationDate`**, not as of today, so a valuation for a past date is checked against the fund as it was on that date. An unregistered fund is `UNKNOWN_FUND` (§4). |
| `valuationDate` | date, `YYYY-MM-DD` | The date the balance is **as of**. Must be a business date and must not be in the future (§4). Never the date the file was produced or sent. |
| `reportedValueCents` | integer | The provider's reported total pooled balance for this fund on this date, in MXN cents. Signed is not permitted here — a pooled balance is not negative — but note that the **NAV struck from it is free to fall**, and nothing in the pipeline clamps it (`docs/ENGINEERING-RULES.md` §4). |
| `providerReference` | string | The provider's own identifier for this valuation. Carried through to the struck NAV so a number in the platform can always be traced back to the statement it came from. Never parsed or interpreted. |

`reportedValueCents` is a non-negative integer of MXN cents: **no decimal
point**, no thousands separator, no currency symbol. A decimal amount is
`DECIMAL_AMOUNT` (§4) and is never rounded — this is the whole point of the
column. Amounts are always MXN; the file carries no currency column because
the platform is MXN-only (`docs/ENGINEERING-RULES.md` §1), and the fund's own base currency
in `reference.fund_identity` is what a struck NAV is denominated in.

**One row per fund per date.** Two rows naming the same fund and the same
valuation date are `DUPLICATE_ROW`, even when they carry the same amount:
a file that says the same thing twice is a producer fault, and guessing
which row was meant is not this format's job.

A file may carry many funds, many dates, or both — a provider reporting ten
funds daily sends ten rows, and a provider backfilling a week sends a
rectangle.

## 4. Rejection contract

**Whole-batch rejection.** No valuation in the file is accepted, and no NAV
is struck from any of it. A file is all-or-nothing for the same reason a
contribution file is: a partially accepted valuation file would leave some
funds priced on this delivery and some not, with nothing recording which.

Checked in this order, cheapest first:

| Order | Check | Error code |
|---|---|---|
| 1 | The uploaded object's SHA-256 matches the `checksumSha256` submitted with the batch | `CHECKSUM_MISMATCH` |
| 2 | The file parses: line 1 is exactly §2's column-header row, and every line after it is a well-formed valuation row | `MALFORMED_FILE` |
| 3 | Every `reportedValueCents` is an integer — a decimal amount is refused, never rounded | `DECIMAL_AMOUNT` |
| 4 | The submitted `declaredRowCount` equals the actual number of valuation rows | `ROW_COUNT_MISMATCH` |
| 5 | No two rows share a `(fundId, valuationDate)` | `DUPLICATE_ROW` |
| 6 | No `valuationDate` is in the future | `FUTURE_VALUATION_DATE` |
| 7 | Every `valuationDate` is a business date (`BusinessCalendar`, Task 7.1) | `NON_BUSINESS_DATE` |
| 8 | Every `fundId` resolves in `reference.fund` as of its own `valuationDate` | `UNKNOWN_FUND` |
| 9 | No row contradicts a valuation already accepted for that `(fundId, valuationDate)` | `VALUATION_ALREADY_LIVE` |

**Check 3 has its own code rather than folding into `MALFORMED_FILE`**
because it is the one parse failure whose *tempting* recovery is wrong. A
provider sending `1234.56` means MXN 1,234.56 and a reader that rounds is
right nine times out of ten and catastrophically wrong the tenth. Naming it
tells the provider exactly what to change; `MALFORMED_FILE` would leave them
guessing at which column and why.

**Check 6 before check 7, deliberately.** A valuation dated next Tuesday is
refused as *future*, not as a *non-business-date*, even when next Tuesday is
also a holiday. "You cannot value a fund on a date that has not happened" is
the useful answer; "next Tuesday is a holiday" invites the provider to
resend it dated next Wednesday, which is also in the future.

**Check 9 is not an overwrite, and never becomes one.** A valuation for a
`(fundId, valuationDate)` already accepted is one of exactly two things:

- **The identical `reportedValueCents`** — an idempotent no-op. The file was
  redelivered, which providers do routinely. The batch is `ACCEPTED`, the
  existing valuation stands, nothing is inserted and no second NAV is
  struck.
- **A different `reportedValueCents`** — a **correction**, which is
  `VALUATION_ALREADY_LIVE` here and goes through Task 20.5 and nowhere else.
  Ingestion refuses it on purpose: a correction supersedes a NAV that
  allotments have already been struck against, so it needs a reason, an
  audit trail and (from Phase 9) four eyes. Letting a redelivered file
  silently reprice a fund would make every one of those controls optional.

`providerReference` is **not** compared. A provider redelivering the same
balance under a new reference is still the same valuation, and treating it
as a contradiction would reject correct files.

**Nothing in a rejection message names a participant**, because nothing in
this file does. Rejection details may name a fund and a valuation date:
neither identifies a person, and both are exactly what the provider needs to
fix the file.

## 5. Missing and late valuations

**A valuation that never arrives is a first-class state, not a silence.**
`docs/ENGINEERING-RULES.md` §2 makes NAV the only thing standing between units and a
balance, so a fund with no valuation for a business date has every
participant's balance quietly stale — and quietly is the problem. Task 3.4's
reconciliation ageing report exists for the same reason on the cash side.

Every `ACTIVE` fund is expected to have exactly one accepted valuation for
every business date from its inception date onward (`O-21`: daily, settled in
`BACKLOG.md` § "Road to the demo"). A business date that has passed with no
accepted valuation appears on the ageing report at
`GET /v1/valuations/ageing`, with how many business dates it is late by, and
is logged as a warning once it passes the configured threshold.

The report is the control. It is not a repair: nothing back-fills a missing
valuation automatically, and no NAV is ever interpolated between two dates.
A price the platform invented is not a price anybody reported.

## 6. Worked example

Two funds, valued on Thursday 2026-08-20, delivered by provider `ACME-CUSTODY`.

The file:

```
fundId,valuationDate,reportedValueCents,providerReference
7b4f1e2a-0c3d-4a5b-9e6f-1a2b3c4d5e6f,2026-08-20,105000000,ACME-2026-08-20-001
9d8c7b6a-5e4f-4321-8765-0fedcba98765,2026-08-20,48250075,ACME-2026-08-20-002
```

The submission that declares it:

```json
{
  "objectKey": "valuations/ACME-CUSTODY/{uuid}-acme-2026-08-20.csv",
  "checksumSha256": "…",
  "source": "ACME-CUSTODY",
  "declaredRowCount": 2
}
```

The first fund's pool is reported at MXN 1,050,000.00 and the second's at
MXN 482,500.75. Neither number is a NAV. If the first fund has 100,000 units
in issue when the strike runs, its NAV per unit is MXN 10.50000000 — but
that division is Task 20.4's, it reads units in issue from the ledger and
never from this file, and the file has no opinion about it.

Redelivering these exact bytes returns the same batch and changes nothing
(§4 check 9, first branch). Delivering `105000001` for the same fund and date
is refused with `VALUATION_ALREADY_LIVE` and has to go through correction.

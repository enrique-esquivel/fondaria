## Project

Rules: `docs/ENGINEERING-RULES.md`, then `docs/DOMAIN.md`, `docs/DESIGN-DECISIONS.md` and `docs/BACKLOG.md` for the area you touch. Correctness of participants' money outranks everything.

**Sensitive areas** (read these hunks yourself; consult the lead before submitting or approving them): cash, units, NAV and rates; ledger ids, linked chains and `LedgerPort`; balance and product rules; tenant scope; authentication and authorization; secrets; migrations and append-only tables; CDC, idempotency and the DLQ.

- `verify` runs `make verify-all` and waits for other builds, kind and Aura's containers. It can take over an hour: wait in the foreground.
- `phase:baseline`: find the cause of each failure. No retries, no longer waits without a stated reason.
- `phase:stack`: one upgrade per PR. Read the release notes between the two versions. The pin, every file that names it, `docs/ENGINEERING-RULES.md` §5 and the ADR that chose it move together.
- `phase:security`: AGENTS.md §4. Coverage in `docs/security/<area>.md`, findings only through `harness finding`.
- This repository is public.

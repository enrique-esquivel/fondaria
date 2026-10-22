-- Task 6.3 -- the indexes the movement-history endpoint reads through.
--
-- WHY. `projection.transfer` was created (V20) with one index per partition, on
-- `ledger_timestamp` -- the replay/audit ordering key. The movement history asks a
-- different question: "the newest movements on THESE accounts", where the accounts
-- are one participant's two holding accounts per fund. Without an index on the leg
-- account, that is a scan of the tenant's whole month partition per page, and a
-- participant with no movements in a month costs the same as one with all of them.
-- At 200,000 participants in one employer, that is the difference between a page
-- read and a table read.
--
-- WHY TWO INDEXES AND NOT ONE. A transfer row carries two account columns, and a
-- movement is a *leg*: one row of `transfer` is one movement for the participant on
-- the credit side and, for a VESTING transfer (UNVESTED -> VESTED, both accounts
-- the same participant's), one movement on each side. The query is therefore a
-- UNION ALL of a debit branch and a credit branch, each an equality on its own
-- account column; an `OR` across the two columns cannot be served by a single
-- index in sorted order, and sorted order is what keyset pagination needs.
--
-- WHY THE ORDER BY IS BAKED IN. The endpoint pages on `(ledger_timestamp,
-- transfer_id)` DESC (Task 6.3). Matching the index order lets each UNION branch be
-- an ordered index scan with its own LIMIT, so a page costs the page, not the
-- history. `transfer_id` is in the index because it is the tiebreaker that makes the
-- keyset total -- two transfers can share a nanosecond timestamp.
--
-- PARTITIONED, SO FUTURE PARTITIONS INHERIT IT. Created on the partitioned parent:
-- Postgres cascades a partitioned index to every existing leaf and to every leaf
-- created later by projection.ensure_tenant_partitions / ensure_transfer_month, so
-- onboarding a tenant needs no extra step. `tenant_id` is deliberately not a column
-- here -- it is the partition key, so it is already established before the index is
-- consulted.
--
-- Additive only: no column, constraint or grant changes, so the previous application
-- version keeps running unchanged (AGENTS.md §4, expand/contract).

CREATE INDEX transfer_by_debit_account
    ON projection.transfer (debit_account_id, ledger_timestamp DESC, transfer_id DESC);

CREATE INDEX transfer_by_credit_account
    ON projection.transfer (credit_account_id, ledger_timestamp DESC, transfer_id DESC);

COMMENT ON INDEX projection.transfer_by_debit_account IS
    'Serves the debit branch of the movement-history query (Task 6.3): newest-first movements whose debit leg is one of a participant''s holding accounts.';
COMMENT ON INDEX projection.transfer_by_credit_account IS
    'Serves the credit branch of the movement-history query (Task 6.3): newest-first movements whose credit leg is one of a participant''s holding accounts.';

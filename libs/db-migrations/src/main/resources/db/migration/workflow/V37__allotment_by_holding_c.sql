-- The daily close's distinct-holdings walk, indexed under the collation it actually
-- compares in.
--
-- WHY. Task 7.4 put participant_internal_ref under COLLATE "C" in
-- JdbcHoldingAccountSource's keyset predicate and ORDER BY, and in JdbcCostBasisSource's
-- range over the same three columns, so the dense snapshot's order (and therefore its
-- hash) is a property of the data rather than of the installed locale. allotment_by_holding
-- (V25) is built under the database's default collation, and a B-tree can only serve a
-- comparison or an ordering in its own collation. So PostgreSQL could seek on
-- organization_id alone: the row comparison became a Filter, every page re-read the rest
-- of its organization's allotment history, and an Incremental Sort put the survivors in
-- order -- on disk. Load run 8 (2026-10-29) measured 840k index rows read per 40k-row page
-- and 333 ms per page, growing by ~600k rows a day; the reconciliation pass went from
-- 4.6 s to 11.1 s in five demo days.
--
-- A column declared COLLATE "C" here is still a plain index column -- not an expression
-- index -- so the walk can be index-only, and both the row comparison
-- (a, b COLLATE "C", c) > (...) and the ORDER BY match it: one seek, then a bounded walk,
-- no sort.
--
-- EXPAND ONLY. allotment_by_holding stays. Nothing still needs its default-collation
-- order -- JdbcCustodianHoldingSource seeks on organization_id and filters on fund_id,
-- which this index serves equally -- so a later contract migration drops it once every
-- running eod-job uses this one (docs/BACKLOG.md). Dropping it here would leave the
-- previous application version reading a table with neither index.
--
-- Not CONCURRENTLY, like every index in this tree: the build holds SHARE on
-- workflow.allotment and blocks allotment-job's inserts for its duration. On a fresh
-- environment that is instant; on a long-lived one, run it between closes.

CREATE INDEX allotment_by_holding_c
    ON workflow.allotment (organization_id, participant_internal_ref COLLATE "C", fund_id);

COMMENT ON INDEX workflow.allotment_by_holding_c IS
    'Serves the daily close''s keyset walk over distinct (organization, participant, fund) holdings and the snapshot''s cost-basis range, both of which compare participant_internal_ref under COLLATE "C" (Task 7.4). Index-only for the walk. Supersedes allotment_by_holding (V25), which is built under the default collation and cannot serve either.';

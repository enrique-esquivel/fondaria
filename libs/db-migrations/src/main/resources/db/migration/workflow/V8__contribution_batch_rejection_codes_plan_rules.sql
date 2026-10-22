-- Step 5 (BACKLOG.md) — Thin Task 2b.2: the first plan-rule rejection codes.
--
-- Expand only. The two new codes are whole-batch, same as every code already
-- in this list: a plan version that cannot be resolved as-of the batch's
-- value date, or a row whose employer amount breaches the plan's matching
-- ratio, rejects the batch entire -- per-row rejection stays Task 3.5's
-- contract, exactly as the original comment on this constraint says. The
-- stale ORGANIZATION_MISMATCH value is left alone; removing it is not this
-- task's to do.

ALTER TABLE workflow.contribution_batch
    DROP CONSTRAINT contribution_batch_rejection_code_known;

ALTER TABLE workflow.contribution_batch
    ADD CONSTRAINT contribution_batch_rejection_code_known
        CHECK (rejection_code IS NULL OR rejection_code IN (
            'CHECKSUM_MISMATCH', 'MALFORMED_FILE', 'ORGANIZATION_MISMATCH',
            'ROW_COUNT_MISMATCH', 'DUPLICATE_ROW', 'SOURCE_SUBTOTAL_MISMATCH',
            'DECLARED_TOTAL_MISMATCH', 'PLAN_VERSION_NOT_FOUND',
            'EMPLOYER_MATCH_RATIO_BREACH'));

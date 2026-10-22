-- Task 20.5 -- correction and supersession.
--
-- V11 already carries everything a correction writes into: supersedes_id, the
-- reason column, and the constraint that a row setting supersedes_id must also
-- carry a reason. What is missing is the guarantee that at most one row may
-- supersede a given original -- without it, two corrections racing against the
-- same live row could both insert, leaving two rows that nothing else
-- supersedes and JdbcFundNavRepository#findLatestLive silently picking
-- whichever has the higher nav_version_id, discarding the other without
-- either caller ever being told.
--
-- This is the same shape as V11's fund_nav_fund_date_original_unique: the
-- concurrency guard lives in the index, not in application code, so
-- FundNavRepository#correct can rely on a conflict rather than a race.

CREATE UNIQUE INDEX fund_nav_supersedes_id_unique
    ON reference.fund_nav (supersedes_id)
    WHERE supersedes_id IS NOT NULL;

COMMENT ON INDEX reference.fund_nav_supersedes_id_unique IS
    'At most one correction per superseded row (Task 20.5) -- what makes FundNavRepository#correct conflict rather than silently create a second row nothing else supersedes.';

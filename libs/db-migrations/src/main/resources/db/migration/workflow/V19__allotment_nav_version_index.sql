-- Task 20.5 -- correction and supersession.
--
-- The report a NAV correction exists to make possible: which allotments were
-- struck at the price just superseded. workflow.allotment (V16) had no index
-- on nav_version_id -- only allotment_by_batch, which serves AllotmentRunner's
-- own lookup by batch, not this query by price.

CREATE INDEX allotment_by_nav_version
    ON workflow.allotment (nav_version_id);

COMMENT ON INDEX workflow.allotment_by_nav_version IS
    'Serves "which allotments used this NAV" (Task 20.5) -- the query a correction''s reason for existing depends on.';

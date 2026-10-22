-- Task 21.2 (ADR-0012) -- which fund a plan's contributions allot into.
--
-- reference.plan_version named a fund it could not validate from the day it was
-- created (V7's own header comment: "a real plan version also carries ...
-- eligible funds ... none of which this table stores yet"). ADR-0012 settled the
-- shape for v1: one fund per plan version, no participant-chosen allocation, no
-- switching, no rebalancing -- so the link is a single column, not a join table.
-- Without it Task 21.2's allotment run has no way to know which fund's NAV a
-- staged row strikes against.
--
-- NULLABLE, DELIBERATELY. Flyway expand/contract (AGENTS.md §4): the previous
-- application version publishes a plan version without knowing this column
-- exists, so a NOT NULL here would stop that version dead. A plan version
-- published before this migration -- or by code that has not picked up the new
-- PlanVersion field -- carries NULL and cannot be allotted against until a
-- corrected version supplies one; that is an honest gap, not a silent wrong
-- fund. Tightening to NOT NULL is a later migration, once no version that omits
-- it is deployed.

ALTER TABLE reference.plan_version
    ADD COLUMN fund_id uuid REFERENCES reference.fund_identity (fund_id);

COMMENT ON COLUMN reference.plan_version.fund_id IS
    'The fund this plan version''s contributions allot into (ADR-0012: one fund per plan version for v1). NULL for a plan version published before this column existed. Read by the allotment run (Task 21.2) to resolve which fund''s NAV a staged row strikes against.';

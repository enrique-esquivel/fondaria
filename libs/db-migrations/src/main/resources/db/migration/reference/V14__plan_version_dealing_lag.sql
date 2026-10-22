-- Task 21.1 — the plan's dealing lag (phase-21-allotment.md).
--
-- "A staged batch is allotted at the NAV strike for a valuation date determined by
-- the batch's value date and the plan's DEALING LAG, never by LocalDate.now()."
--
-- WHY reference.plan_version AND NOT CONFIGURATION. AGENTS.md §4: contribution
-- caps, product configuration and plan rules are effective-dated versioned data --
-- never constants, never environment config. A dealing lag is a plan rule: two
-- employers on the same platform can deal at different lags, and an employer that
-- renegotiates its lag needs the old value to stay readable, because a batch
-- allotted last March was priced under the lag in force last March and that
-- calculation has to be reproducible years later from stored data.
--
-- EXPAND/CONTRACT. NOT NULL with a DEFAULT of 0, so the previous application
-- version -- which does not know this column exists -- keeps inserting plan
-- versions successfully and gets same-day dealing, which is what it already
-- assumed. Nothing has to backfill and nothing has to be deployed in order.
--
-- WHY THE UPPER BOUND. Advancing N business days is a loop over the calendar, and
-- a garbage lag would turn a read of a batch's status into a spin. A lag beyond a
-- month is not a lag, it is a defect; DealingDateRule enforces the identical bound
-- so the rule is safe against a value this table never saw.

ALTER TABLE reference.plan_version
    ADD COLUMN dealing_lag_business_days integer NOT NULL DEFAULT 0;

ALTER TABLE reference.plan_version
    ADD CONSTRAINT plan_version_dealing_lag_in_range
        CHECK (dealing_lag_business_days >= 0 AND dealing_lag_business_days <= 30);

COMMENT ON COLUMN reference.plan_version.dealing_lag_business_days IS
    'Business days between a batch''s value date and the valuation date it deals at (Task 21.1). Zero means same-day dealing. Applied by DealingDateRule against BusinessCalendar, never against the system clock; cut-off can push the result later but never earlier -- a batch is never back-dated.';

-- Task D.1 -- the simulated clock. One row holding how far the demo has moved
-- time forward, polled by SimulatedClock in every service that derives "today".
--
-- DEVIATION FROM ADR-0011, DELIBERATE AND NARROW. reference holds
-- effective-dated data, resolved as-of a date and never UPDATEd (V1's default
-- privileges grant fondaria_app only SELECT/INSERT here) -- reference.fund_nav
-- (V11), reference.plan_version, reference.provider_account (V26) all follow
-- that shape. This table does not: it is one row that is a dial, not a
-- history, and there is deliberately nothing to version -- "how far the demo
-- has moved" has no prior values worth keeping, unlike a NAV correction or a
-- superseded plan. It stays in reference because it is effective-dated data a
-- service reads directly and never a status lifecycle (workflow) or a ledger
-- fact -- and because the exception is one narrow GRANT below, not a
-- different privilege model for the schema.
--
-- id is fixed at 1 by the check constraint below: there is exactly one demo
-- clock for the whole platform, matching the one wall clock every service
-- otherwise shares.

-- Bounded well above any demo's realistic horizon (10 years) and comfortably inside
-- java.time.Instant's range, so SimulatedClock#instant() (delegate.plusSeconds(offset)) can never
-- overflow no matter what is written here -- the bound is enforced at the point that matters
-- (the write), not by defensive arithmetic in every reader.
CREATE TABLE reference.demo_clock (
    id             integer     NOT NULL,
    offset_seconds bigint      NOT NULL,
    updated_at     timestamptz NOT NULL,

    CONSTRAINT demo_clock_pk
        PRIMARY KEY (id),
    CONSTRAINT demo_clock_singleton
        CHECK (id = 1),
    CONSTRAINT demo_clock_offset_not_negative
        CHECK (offset_seconds >= 0),
    CONSTRAINT demo_clock_offset_bounded
        CHECK (offset_seconds <= 315360000)
);

COMMENT ON TABLE reference.demo_clock IS
    'The platform''s current simulated time offset (Task D.1) -- one row, id = 1. SimulatedClock in contribution-api, allotment-job, valuation-api, eod-job, statement-worker and balance-api polls this row once a second when DEMO_CLOCK_ENABLED=true; instant() = real time + offset_seconds. Never read when the flag is unset. The only reference table fondaria_app may UPDATE: it is the one piece of reference data that is a dial, not a history.';
COMMENT ON COLUMN reference.demo_clock.offset_seconds IS
    'Monotonically increasing -- demo_clock_forbid_decrease (a BEFORE UPDATE trigger, since a CHECK cannot compare NEW against OLD) rejects any UPDATE that would lower it, and demo_clock_offset_bounded caps it at 315,360,000 seconds (10 years). Written exclusively by libs/demo-tools (Task D.2) over a direct JDBC connection -- deliberately not through an API endpoint (a clock-advance endpoint on a money service is an attack surface no production deployment wants, even disabled).';

-- reference's default privileges (V1) grant fondaria_app only SELECT and
-- INSERT -- correct for every other table in this schema, wrong for a row
-- that is meant to be dialed forward in place. This is the one deliberate
-- exception, scoped to this table alone.
GRANT UPDATE ON reference.demo_clock TO fondaria_app;

-- "The offset only ever increases" is a design invariant, not just a convention demo-tools is
-- trusted to follow -- enforced here, atomic with the write, the same way every other invariant
-- in this platform that has consequences is (AGENTS.md: "belongs above the port" is for product
-- policy; a structural fact about one row is enforced in the row itself). A CHECK constraint
-- cannot reference the row's previous value, so this needs a trigger. The trigger function runs
-- with the invoking role's own privileges (no SECURITY DEFINER), and Postgres invokes it
-- internally as part of the UPDATE -- the triggering role needs no separate EXECUTE grant on it.
CREATE FUNCTION reference.demo_clock_forbid_decrease() RETURNS trigger AS $$
BEGIN
    IF NEW.offset_seconds < OLD.offset_seconds THEN
        RAISE EXCEPTION 'reference.demo_clock.offset_seconds must not decrease (was %, attempted %)',
            OLD.offset_seconds, NEW.offset_seconds;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER demo_clock_forbid_decrease
    BEFORE UPDATE ON reference.demo_clock
    FOR EACH ROW
    EXECUTE FUNCTION reference.demo_clock_forbid_decrease();

-- The one row this table ever holds. Offset zero: a freshly migrated
-- environment (including production, where DEMO_CLOCK_ENABLED is never set
-- and this row is therefore never read at all) starts with simulated time
-- equal to real time.
INSERT INTO reference.demo_clock (id, offset_seconds, updated_at)
VALUES (1, 0, now());

-- Task 20.4 — the NAV store and the strike.
--
-- The DDL comes from phase-05-projection.md Task 5.1, otherwise unchanged, moved from
-- `projection` to `reference`: ADR-0011 reserves `projection` for CDC-fed data no service
-- writes, and a struck NAV is written directly by valuation-api and is rule-evaluation
-- evidence, not a replicated fact. `fund_id` is adapted from that sketch's placeholder
-- `INT` to `uuid`, matching `reference.fund_identity.fund_id` (Task 20.1) rather than the
-- pre-FundId draft.
--
-- units_in_issue travels with the price rather than being looked up again later: a stored
-- row reproduces its own arithmetic years afterward from data alone, never by rerunning
-- current code (docs/ENGINEERING-RULES.md §4).

CREATE TABLE reference.fund_nav (
    nav_version_id   bigserial   PRIMARY KEY,
    fund_id          uuid        NOT NULL REFERENCES reference.fund_identity (fund_id),
    valuation_date   date        NOT NULL,
    nav_scaled       bigint      NOT NULL CHECK (nav_scaled > 0),
    units_in_issue   bigint      NOT NULL CHECK (units_in_issue >= 0),
    source           text        NOT NULL,
    supersedes_id    bigint      REFERENCES reference.fund_nav (nav_version_id),
    reason           text,
    published_at     timestamptz NOT NULL,

    CONSTRAINT fund_nav_source_present
        CHECK (length(btrim(source)) > 0),
    -- A correction (Task 20.5) sets supersedes_id and must carry why; an original strike
    -- carries neither.
    CONSTRAINT fund_nav_reason_required_when_superseding
        CHECK (supersedes_id IS NULL OR length(btrim(reason)) > 0)
);

COMMENT ON TABLE reference.fund_nav IS
    'Struck NAV per fund per valuation date (Task 20.4), append-only and versioned. Never a balance and never the ledger -- the price sits outside the double-entry system (docs/ENGINEERING-RULES.md §2).';
COMMENT ON COLUMN reference.fund_nav.units_in_issue IS
    'Read from LedgerPort.lookupAccount at strike time, never summed from PostgreSQL (ADR-0008), and stored so the division is reproducible without rerunning current code.';
COMMENT ON COLUMN reference.fund_nav.source IS
    'Which valuation model struck this price -- "MODEL_B" today (docs/DOMAIN.md).';
COMMENT ON COLUMN reference.fund_nav.supersedes_id IS
    'Set only by a correction (Task 20.5). A wrong NAV is never deleted or updated -- a correction is a new row alongside it.';

-- One live (non-superseding) NAV per fund per date -- what makes the strike idempotent:
-- a second strike attempt for a date already struck conflicts here rather than inserting
-- a second row.
CREATE UNIQUE INDEX fund_nav_fund_date_original_unique
    ON reference.fund_nav (fund_id, valuation_date)
    WHERE supersedes_id IS NULL;

-- The strike's own lookup: one row for a given fund and date.
CREATE INDEX fund_nav_fund_date_idx
    ON reference.fund_nav (fund_id, valuation_date);

-- Defense in depth beyond reference's schema-level default privileges (V1__schemas_and_grants.sql),
-- which already never grant UPDATE or DELETE to fondaria_app. This table is the platform's price of
-- record, so the guarantee is made explicit and structural rather than resting solely on a default
-- that a future migration to `reference` could not accidentally loosen for this table specifically:
-- an explicit revoke, and a trigger that raises for ANY role, including one that runs migrations
-- with elevated privileges.
REVOKE UPDATE, DELETE ON reference.fund_nav FROM fondaria_app;
REVOKE UPDATE, DELETE ON reference.fund_nav FROM PUBLIC;

CREATE FUNCTION reference.fund_nav_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'reference.fund_nav is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER fund_nav_no_update_or_delete
    BEFORE UPDATE OR DELETE ON reference.fund_nav
    FOR EACH ROW EXECUTE FUNCTION reference.fund_nav_immutable();

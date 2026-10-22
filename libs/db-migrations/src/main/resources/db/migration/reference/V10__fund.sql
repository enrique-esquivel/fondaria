-- Task 20.1 — the fund registry.
--
-- Two tables, not one, because the fields the phase doc lists split cleanly
-- along a lifecycle boundary that a single append-only table cannot express:
--
-- fund_identity holds what is fixed at a fund's first registration and never
-- changes again -- the TigerBeetle unit ledger id (AGENTS.md §4: a fund is
-- never split across clusters, so its ledger id is assigned once and never
-- reused), the base currency, the unit scale, the inception date and the
-- opening NAV per unit the first contribution buys at. Uniqueness of
-- unit_ledger_id across every fund that has ever existed is exactly what
-- "two funds cannot claim the same unit ledger id" (Task 20.1 acceptance
-- criterion 2) needs, and a plain UNIQUE constraint can only mean that if
-- these columns never repeat across versions of the same fund.
--
-- fund holds what genuinely varies over time -- name, vehicle type, status --
-- effective-dated and append-only, the pattern reference.plan_version set
-- (V7__plan_version.sql): a correction is a new version, never an edit,
-- enforced by reference's schema-level default privileges (V1), which grant
-- SELECT and INSERT and never UPDATE or DELETE.

CREATE TABLE reference.fund_identity (
    fund_id             uuid        PRIMARY KEY,
    unit_ledger_id      bigint      NOT NULL,
    base_currency       text        NOT NULL,
    unit_scale          bigint      NOT NULL,
    inception_date      date        NOT NULL,
    opening_nav_scaled  bigint      NOT NULL,
    created_at          timestamptz NOT NULL,

    CONSTRAINT fund_identity_unit_ledger_id_unique
        UNIQUE (unit_ledger_id),
    CONSTRAINT fund_identity_unit_scale_positive
        CHECK (unit_scale > 0),
    CONSTRAINT fund_identity_opening_nav_positive
        CHECK (opening_nav_scaled > 0)
);

COMMENT ON TABLE reference.fund_identity IS
    'Birth facts of a fund, one row per fund_id, fixed forever from Task 20.1 registration onward. Never referenced by an UPDATE -- there is nothing here a later version of the fund is allowed to change.';
COMMENT ON COLUMN reference.fund_identity.unit_ledger_id IS
    'The TigerBeetle ledger id (from 1001, LedgerCodes) carrying this fund''s units. Assigned once and never reused by another fund -- enforced by the UNIQUE constraint above, not by discipline.';
COMMENT ON COLUMN reference.fund_identity.unit_scale IS
    'The fixed-point scale units are stored at -- always Units.SCALE (1e-8), carried as data rather than a constant so a stored row reproduces its own arithmetic without rerunning current code (AGENTS.md §4).';
COMMENT ON COLUMN reference.fund_identity.opening_nav_scaled IS
    'The price, scaled 1e-8 in the fund''s base currency, the fund''s first contribution buys at (docs/DOMAIN.md). Configuration, not a constant.';

CREATE TABLE reference.fund (
    fund_id        uuid        NOT NULL REFERENCES reference.fund_identity (fund_id),
    effective_from date        NOT NULL,
    version        integer     NOT NULL,
    name           text        NOT NULL,
    vehicle_type   text        NOT NULL,
    status         text        NOT NULL,
    published_at   timestamptz NOT NULL,

    CONSTRAINT fund_pk
        PRIMARY KEY (fund_id, effective_from, version),
    CONSTRAINT fund_name_present
        CHECK (length(btrim(name)) > 0),
    CONSTRAINT fund_vehicle_type_present
        CHECK (length(btrim(vehicle_type)) > 0),
    CONSTRAINT fund_status_known
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

COMMENT ON TABLE reference.fund IS
    'Effective-dated fund versions (Task 20.1). Resolution is always as-of a date: the highest (effective_from, version) not after the date asked for -- the same rule reference.plan_version resolves by.';
COMMENT ON COLUMN reference.fund.vehicle_type IS
    'Free text, matching the product configuration model''s vehicle_type axis (docs/DOMAIN.md) -- e.g. CAJA_DE_AHORRO, the only vehicle v1 ships (ADR-0004). Configuration, not an enum, so a new vehicle is a published row, not a code change.';
COMMENT ON COLUMN reference.fund.version IS
    'Disambiguates two versions published with the same effective_from -- a correction of a mistaken publication. The higher value wins resolution; the lower one remains readable.';

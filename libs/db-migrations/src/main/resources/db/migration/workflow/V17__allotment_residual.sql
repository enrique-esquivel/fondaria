-- Task 21.3 -- unit rounding and residue.
--
-- AllotmentService floors every participant-source contribution to the unit scale
-- (Units.SCALE, 1e-8): the cash still enters FUND_CASH in full, but the sub-tick
-- fraction of a unit that floor discards is never issued. docs/rounding-policy.md
-- states the policy; this table is what makes the discarded fraction's cash value
-- visible and auditable instead of an unrecorded gain nobody can see. It is a
-- record, never a ledger posting -- workflow.allotment (V16) still shows
-- UNITS_IN_ISSUE debited by exactly the sum credited to participants, unchanged by
-- this table's existence.
--
-- One row per allotment run. A "run" is one AllotmentService#allot call, i.e. one
-- STAGED batch (ADR-0012: one fund per plan version, so a batch never spans funds),
-- the same granularity workflow.allotment's own audit trail uses.

CREATE TABLE workflow.allotment_residual (
    batch_id               uuid        NOT NULL REFERENCES workflow.contribution_batch (batch_id),
    organization_id        uuid        NOT NULL REFERENCES master.organization (organization_id),
    fund_id                uuid        NOT NULL REFERENCES reference.fund_identity (fund_id),
    nav_version_id         bigint      NOT NULL REFERENCES reference.fund_nav (nav_version_id),
    row_count              integer     NOT NULL,
    residual_minor_units   bigint      NOT NULL,
    recorded_at            timestamptz NOT NULL,

    CONSTRAINT allotment_residual_pk
        PRIMARY KEY (batch_id),
    CONSTRAINT allotment_residual_row_count_positive
        CHECK (row_count > 0),
    -- docs/ENGINEERING-RULES.md §4: rounding is always in the fund's favour, never a participant's.
    -- A negative residual means units were over-issued somewhere -- a run must fail
    -- before it ever reaches this insert, never be recorded and absorbed silently.
    CONSTRAINT allotment_residual_non_negative
        CHECK (residual_minor_units >= 0)
);

COMMENT ON TABLE workflow.allotment_residual IS
    'One row per allotment run (docs/rounding-policy.md, Task 21.3): the total cash value discarded by flooring every participant-source contribution to the unit scale. Never a ledger posting -- FUND_CASH already holds this value in full, and UNITS_IN_ISSUE is never debited by it. Never updated or deleted; append-only the same as workflow.allotment.';
COMMENT ON COLUMN workflow.allotment_residual.row_count IS
    'The number of participant-source contribution legs priced this run (one floor division each) -- what bounds the residual: at most one sub-tick fraction of a unit''s value per leg.';
COMMENT ON COLUMN workflow.allotment_residual.residual_minor_units IS
    'MXN cents: cash allotted minus Money.value(units issued, nav). Always >= 0. Reconciles independently against FUND_CASH minus the value of units issued.';

REVOKE UPDATE, DELETE ON workflow.allotment_residual FROM fondaria_app;
REVOKE UPDATE, DELETE ON workflow.allotment_residual FROM PUBLIC;

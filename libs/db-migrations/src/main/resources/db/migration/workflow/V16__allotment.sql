-- Task 21.2 -- the allotment run turns a staged contribution batch into units.
--
-- Two changes, one concern: recording that a batch has been allotted.
--
-- 1. ALLOTTED joins the batch status lifecycle. ContributionBatch.isAwaitingAllotment's
--    own javadoc anticipated this: "allotment writes no status of its own -- Task 21.2
--    builds the run." It is terminal, the same way REJECTED is: nothing further ever
--    moves a batch out of ALLOTTED, so no new lease or timestamp column is needed
--    beyond status_updated_at, which already records when a status last changed.
--
-- 2. workflow.allotment is the audit trail AGENTS.md §4 requires: "every unit allotment
--    records which NAV version it used. Without that link, correcting a NAV error is
--    impossible." One row per (batch, participant, source) -- exactly the granularity
--    of one unit-ledger transfer in the CONTRIBUTION_ALLOTMENT chain
--    (chart-of-accounts.md §4), so a row and a transfer correspond one for one. Never
--    updated or deleted, the same rule AGENTS.md §4 states for transfer, fund_nav and
--    participant_units: a correction is a new row, never an edit to this one.

ALTER TABLE workflow.contribution_batch
    DROP CONSTRAINT contribution_batch_status_known;

ALTER TABLE workflow.contribution_batch
    ADD CONSTRAINT contribution_batch_status_known
        CHECK (status IN ('RECEIVED', 'VALIDATING', 'VALIDATED', 'PENDING_RECONCILIATION',
                           'STAGED', 'ALLOTTED', 'REJECTED'));

COMMENT ON COLUMN workflow.contribution_batch.status IS
    'RECEIVED -> VALIDATING -> STAGED -> ALLOTTED | PENDING_RECONCILIATION | REJECTED. VALIDATED is the decision -- structurally sound, plan-rule validated, cash matched to the cent -- that BatchValidationRunner reaches and immediately stages from; nothing in this service rests a batch there durably. PENDING_RECONCILIATION is not terminal: the file is structurally sound and its cash has not arrived, so the poller re-runs the gate alone. ALLOTTED is terminal (Task 21.2): every staged row has been priced and posted to the ledger.';

CREATE TABLE workflow.allotment (
    organization_id           uuid        NOT NULL REFERENCES master.organization (organization_id),
    batch_id                  uuid        NOT NULL REFERENCES workflow.contribution_batch (batch_id),
    participant_internal_ref  text        NOT NULL,
    source                    text        NOT NULL,
    fund_id                   uuid        NOT NULL REFERENCES reference.fund_identity (fund_id),
    nav_version_id            bigint      NOT NULL REFERENCES reference.fund_nav (nav_version_id),
    units_scaled              bigint      NOT NULL,
    vested                    boolean     NOT NULL,
    transfer_id               uuid        NOT NULL,
    allotted_at               timestamptz NOT NULL,

    CONSTRAINT allotment_pk
        PRIMARY KEY (organization_id, batch_id, participant_internal_ref, source),
    CONSTRAINT allotment_transfer_id_unique
        UNIQUE (transfer_id),
    CONSTRAINT allotment_source_known
        CHECK (source IN ('EMPLOYEE', 'EMPLOYER', 'VOLUNTARY')),
    CONSTRAINT allotment_units_positive
        CHECK (units_scaled > 0)
);

COMMENT ON TABLE workflow.allotment IS
    'One row per (batch, participant, source) allotted -- the audit trail AGENTS.md §4 requires: which NAV version priced this unit issuance. Never updated or deleted; a correction is a new row (Task 20.5, post-demo). One row corresponds to exactly one unit-ledger transfer in the CONTRIBUTION_ALLOTMENT chain (chart-of-accounts.md §4).';
COMMENT ON COLUMN workflow.allotment.source IS
    'EMPLOYEE and VOLUNTARY always land vested (chart-of-accounts.md §3: no vesting applies to a participant''s own money); EMPLOYER lands vested or unvested depending on the plan version''s vesting_full_years at allotment time.';
COMMENT ON COLUMN workflow.allotment.nav_version_id IS
    'The reference.fund_nav row this allotment struck units at. AGENTS.md §4: without this link, correcting a NAV error is impossible.';
COMMENT ON COLUMN workflow.allotment.units_scaled IS
    'Units issued, scale 1e-8 (Units.SCALE), floored -- AGENTS.md §4, integer arithmetic only. The sub-tick remainder is not issued and is not recorded here; Task 21.3 is what makes that residue visible.';
COMMENT ON COLUMN workflow.allotment.transfer_id IS
    'The deterministically-derived unit-ledger TransferId this row records, so the same (batch, participant, source) rerun always names the transfer the ledger itself already recognizes as existing.';

CREATE INDEX allotment_by_batch
    ON workflow.allotment (batch_id);

REVOKE UPDATE, DELETE ON workflow.allotment FROM fondaria_app;
REVOKE UPDATE, DELETE ON workflow.allotment FROM PUBLIC;

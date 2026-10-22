-- Task 3.6 — staging for allotment (ADR-0011).
--
-- ADR-0011 puts this in `workflow`: service-owned operational state, written
-- directly by contribution-api, produced by no CDC stream. It is not
-- `reference` -- nothing here is effective-dated rulebook data -- and it is
-- not the ledger: these are cash amounts, not units. NAV strike and unit
-- allotment are Phase 21; this table stops at "ready to allot".
--
-- WHERE THE ROW DATA COMES FROM. The parsed file is gone by the time a
-- batch's cash reconciles -- a PENDING_RECONCILIATION batch can wait many
-- sweeps, each of which re-runs only the gate (file_validated_at is what
-- skips the re-parse, V5). So a row is captured here once, at the same
-- point auto-enrolment runs (BatchValidationRunner.readAndCheckFile, after
-- every structural and plan-rule check of contribution-file-v2.md §4
-- passes) -- never re-read from the object store afterwards. A batch that
-- reaches that point can no longer be rejected (every remaining check has
-- already run), so a row captured here is guaranteed to eventually belong
-- to a STAGED batch, whatever the cash side is still waiting on.
--
-- WHY THIS DOES NOT MAKE THE ROW "STAGED" PREMATURELY. "The staged set" is
-- answered by joining this table to workflow.contribution_batch and
-- filtering status = 'STAGED' -- captured rows for a batch still sitting in
-- PENDING_RECONCILIATION simply do not appear in that query yet. No second
-- write promotes a row later: the fact ("what this row contributed, and
-- when") never changes, only which batches its parent currently satisfies a
-- query for.

ALTER TABLE workflow.contribution_batch
    DROP CONSTRAINT contribution_batch_status_known;

ALTER TABLE workflow.contribution_batch
    ADD CONSTRAINT contribution_batch_status_known
        CHECK (status IN ('RECEIVED', 'VALIDATING', 'VALIDATED', 'PENDING_RECONCILIATION',
                           'STAGED', 'REJECTED'));

ALTER TABLE workflow.contribution_batch
    DROP CONSTRAINT contribution_batch_reconcilable_has_total;

ALTER TABLE workflow.contribution_batch
    ADD CONSTRAINT contribution_batch_reconcilable_has_total
        CHECK (status NOT IN ('VALIDATED', 'PENDING_RECONCILIATION', 'STAGED')
               OR declared_total_minor_units IS NOT NULL);

COMMENT ON COLUMN workflow.contribution_batch.status IS
    'RECEIVED -> VALIDATING -> STAGED | PENDING_RECONCILIATION | REJECTED. VALIDATED is the decision -- structurally sound, plan-rule validated, cash matched to the cent -- that BatchValidationRunner reaches and immediately stages from; nothing in this service rests a batch there durably. PENDING_RECONCILIATION is not terminal: the file is structurally sound and its cash has not arrived, so the poller re-runs the gate alone.';

CREATE TABLE workflow.contribution_batch_row (
    organization_id            uuid        NOT NULL REFERENCES master.organization (organization_id),
    batch_id                   uuid        NOT NULL REFERENCES workflow.contribution_batch (batch_id),
    participant_internal_ref   text        NOT NULL,
    participant_payroll_ref    text        NOT NULL,
    plan_id                    text        NOT NULL,
    payroll_period              text        NOT NULL,
    value_date                 date        NOT NULL,
    employee_minor_units       bigint      NOT NULL,
    employer_minor_units       bigint      NOT NULL,
    voluntary_minor_units      bigint      NOT NULL,
    recorded_at                timestamptz NOT NULL,

    CONSTRAINT contribution_batch_row_pk
        PRIMARY KEY (organization_id, batch_id, participant_internal_ref),
    CONSTRAINT contribution_batch_row_amounts_non_negative
        CHECK (employee_minor_units >= 0 AND employer_minor_units >= 0
               AND voluntary_minor_units >= 0)
);

COMMENT ON TABLE workflow.contribution_batch_row IS
    'One participant''s contribution from a structurally-valid batch, captured once at structural-validation time (contribution-file-v2.md, mirroring master.participant''s auto-enrolment). Immutable: a correction is a new staged batch referencing the original, never an edit here. "The staged set" (Task 3.6) is this table joined to workflow.contribution_batch and filtered to status = ''STAGED''.';
COMMENT ON COLUMN workflow.contribution_batch_row.participant_internal_ref IS
    'Pseudonymous, matching master.participant.participant_internal_ref. No name, RFC or CURP.';
COMMENT ON COLUMN workflow.contribution_batch_row.employee_minor_units IS
    'MXN cents from this row''s EMPLOYEE column. bigint, never numeric (AGENTS.md §4). Cash staged for allotment, never a unit and never a balance.';

-- The poller sweeps a batch's own rows; the ageing and status paths sweep by
-- organization and period. No index on status alone: it is always queried
-- through the parent batch, never on this table by itself.
CREATE INDEX contribution_batch_row_by_organization_period
    ON workflow.contribution_batch_row (organization_id, payroll_period);

REVOKE UPDATE, DELETE ON workflow.contribution_batch_row FROM fondaria_app;
REVOKE UPDATE, DELETE ON workflow.contribution_batch_row FROM PUBLIC;

-- Task 7.5a -- the daily close's sign-off: the single fact that the book for a business
-- date has been proved three ways and may now be reported from.
--
-- WHY workflow. ADR-0011: workflow holds service-owned operational state. A sign-off is
-- the terminal fact of the gated chain workflow.reconciliation_run (V25),
-- workflow.custodian_reconciliation_run (V27) and workflow.snapshot_run (V28) record the
-- steps of. It is not effective-dated reference data and nothing resolves it as-of a date
-- -- it is looked up by exact business date, and either the row is there or it is not.
--
-- WHY ONE ROW PER DATE, AND NO STATUS. Unlike the three run tables this depends on, a
-- sign-off has no RUNNING -> PASSED | FAILED lifecycle: eod-job's SignoffService checks
-- the three upstream gates and, only if all three are open, writes this row. A row means
-- signed off. There is deliberately no supersession and no revoked state -- a
-- reconciliation re-run that fails AFTER a date is signed off is an operational incident
-- (Task 7.6's "missed sign-off" runbook), not a state this table models. Phase 9's
-- four-eyes sign-off is where a revocation path belongs if one is ever wanted.
--
-- WHY announced_at IS NULLABLE. The row is the system of record; close.signed-off.v1 is
-- derived from it (ADR-0021). SignoffService writes this row first (announced_at NULL),
-- publishes the event, then sets announced_at when Redpanda confirms. A process that dies
-- between the write and the publish leaves a row that says "signed off, not yet
-- announced", and the next sweep re-publishes -- the platform's standard rule for
-- recording two genuine facts (ADR-0011, AGENTS.md "Data flow"): the non-ledger fact
-- first, idempotent on a derived key, a crash between them visible rather than silent.
--
-- NOT APPEND-ONLY. announced_at is set by a later UPDATE, so this table takes workflow's
-- ordinary grant -- like the run tables' status column, unlike reconciliation_break
-- (V25) which is evidence and is locked. A DELETE is left possible on purpose: re-opening
-- a wrongly-signed-off date is Task 7.6's runbook, and it should not need a migration.
--
-- NO announced_at >= signed_off_at CHECK. An earlier version had one. It guards nothing --
-- only SignoffService's guarded UPDATE ever sets announced_at, and it clamps the value to
-- GREATEST(now, signed_off_at) precisely so the invariant holds without a constraint. A
-- CHECK here would only ever fire on host clock skew between two eod-job instances, and its
-- failure mode is a permanently un-announced date re-publishing its event on every sweep --
-- strictly worse than a timestamp a few milliseconds off.

CREATE TABLE workflow.close_signoff (
    business_date            date        NOT NULL,
    signed_off_at            timestamptz NOT NULL,
    reconciliation_run_id    uuid        NOT NULL
        REFERENCES workflow.reconciliation_run (run_id),
    custodian_run_id         uuid        NOT NULL
        REFERENCES workflow.custodian_reconciliation_run (run_id),
    snapshot_run_id          uuid        NOT NULL
        REFERENCES workflow.snapshot_run (run_id),
    snapshot_position_count  bigint      NOT NULL,
    snapshot_object_key      text        NOT NULL,
    snapshot_manifest_key    text        NOT NULL,
    announced_at             timestamptz,

    CONSTRAINT close_signoff_pk
        PRIMARY KEY (business_date),
    CONSTRAINT close_signoff_position_count_not_negative
        CHECK (snapshot_position_count >= 0)
);

COMMENT ON TABLE workflow.close_signoff IS
    'One row per business date whose daily close has been signed off (Task 7.5a): internal reconciliation (7.2), custodian reconciliation (7.3) and the dense snapshot (7.4) have all passed. The gate statement generation (7.5b) reads -- no row, no statements. Written by eod-job''s SignoffService; the row is the system of record, close.signed-off.v1 is derived from it.';
COMMENT ON COLUMN workflow.close_signoff.business_date IS
    'The close this sign-off belongs to, from BusinessCalendar -- never the wall clock. Primary key: a date is signed off at most once, and a second SignoffService pass for the same date is a no-op (ON CONFLICT DO NOTHING).';
COMMENT ON COLUMN workflow.close_signoff.reconciliation_run_id IS
    'The workflow.reconciliation_run this date was signed off against -- the exact PASSED run, so an auditor can reconstruct which evidence authorised the sign-off rather than trusting that some run passed at some point.';
COMMENT ON COLUMN workflow.close_signoff.snapshot_position_count IS
    'workflow.snapshot_run.position_count for the snapshot of record on this date -- the control total Task 7.5b''s statement fan-out must generate exactly, carried on close.signed-off.v1 so the scheduler needs no second read to know how many jobs to expect.';
COMMENT ON COLUMN workflow.close_signoff.snapshot_object_key IS
    'The positions file''s object-storage key, copied from workflow.snapshot_run so close.signed-off.v1 is fully derivable from this one row -- a re-publish after a crash needs no second read of snapshot_run, whose "of record" row could in principle have moved.';
COMMENT ON COLUMN workflow.close_signoff.announced_at IS
    'When close.signed-off.v1 was confirmed by Redpanda, clamped to GREATEST(now, signed_off_at). Null means the row is written but the event is not yet out -- a crash between the two, or a broker outage. Every SignoffService sweep re-publishes EVERY row still null here, not just the current business date, so a broker outage that outlasts the cut-off still recovers. A consumer of the event is idempotent on business_date, so a re-publish is harmless.';

-- The gate's read: "is this date signed off". Single-row lookup by the primary key, so no
-- extra index. The scheduler in statement-worker (7.5b) also reads by business_date.

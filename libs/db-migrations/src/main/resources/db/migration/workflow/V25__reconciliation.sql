-- Task 7.2 -- internal reconciliation: the record of a daily-close tie-out.
--
-- WHY workflow AND NOT reference. ADR-0011: `reference` holds effective-dated data and
-- rule-evaluation evidence; `workflow` holds service-owned status lifecycles. A
-- reconciliation run is a lifecycle -- it starts RUNNING and ends PASSED or FAILED --
-- and the sign-off gate (Task 7.5) reads that status. It is not a rule evaluation and
-- nothing resolves it as-of a date.
--
-- WHY TWO TABLES. The run is the gate; the breaks are the evidence. The gate has to be
-- readable in one indexed row lookup by anything downstream ("did the close pass for
-- this date"), and it must be answerable even when a run produced a hundred thousand
-- breaks. The status column therefore lives on a table nothing has to aggregate.
--
-- THE RUN'S STATUS IS UPDATED IN PLACE, THE BREAKS ARE NOT. RUNNING -> PASSED | FAILED
-- is exactly the lifecycle `workflow.contribution_batch` already models, and the same
-- grants apply. A break is a finding, and a finding is never edited: the same rule
-- AGENTS.md §4 states for transfer, fund_nav, participant_units and allotment. A break
-- that turns out to be explained is not deleted -- the next run simply does not raise it.
--
-- EVERY AMOUNT HERE IS SIGNED. AGENTS.md §4: values go down, and a drift has a
-- direction. There is deliberately no CHECK (drift >= 0): "the ledger holds less than
-- the projection says" is exactly as real a finding as its opposite, and clamping one of
-- them to zero would hide half of what this control exists to find.

CREATE TABLE workflow.reconciliation_run (
    run_id                 uuid        NOT NULL,
    business_date          date        NOT NULL,
    status                 text        NOT NULL,
    started_at             timestamptz NOT NULL,
    completed_at           timestamptz,
    duration_millis        bigint,
    fund_count             integer     NOT NULL DEFAULT 0,
    holding_account_count  bigint      NOT NULL DEFAULT 0,
    cash_account_count     integer     NOT NULL DEFAULT 0,
    break_count            integer     NOT NULL DEFAULT 0,

    CONSTRAINT reconciliation_run_pk
        PRIMARY KEY (run_id),
    CONSTRAINT reconciliation_run_status_known
        CHECK (status IN ('RUNNING', 'PASSED', 'FAILED')),
    CONSTRAINT reconciliation_run_settled_when_complete
        CHECK ((status = 'RUNNING') = (completed_at IS NULL)),
    CONSTRAINT reconciliation_run_counts_not_negative
        CHECK (fund_count >= 0 AND holding_account_count >= 0
               AND cash_account_count >= 0 AND break_count >= 0)
);

COMMENT ON TABLE workflow.reconciliation_run IS
    'One internal-reconciliation pass (Task 7.2): three independent derivations of units per fund, plus cash, compared for exact agreement. The gate the rest of the daily close reads -- statements do not render off a date whose latest run is not PASSED (Task 7.5).';
COMMENT ON COLUMN workflow.reconciliation_run.business_date IS
    'The close this run belongs to, from BusinessCalendar -- never the wall clock and never a date the caller invented. Several runs may exist for one date: a re-run after a break is investigated is a new row, never an edit to the old one.';
COMMENT ON COLUMN workflow.reconciliation_run.status IS
    'RUNNING -> PASSED | FAILED. Updated in place, the same lifecycle workflow.contribution_batch models. FAILED means the close is blocked, whether by drift (break_count > 0) or by the pass itself erroring before it could compare anything (break_count = 0) -- there is deliberately no constraint tying the two, because an unfinished comparison is not a pass either. A run whose process dies stays RUNNING, and the gate treats anything other than PASSED as blocked, so a lost process fails closed.';
COMMENT ON COLUMN workflow.reconciliation_run.duration_millis IS
    'How long the pass took. Recorded because the phase requires it: "completes for 1 million participants within the window; duration recorded". A regression here is visible without instrumenting anything else.';
COMMENT ON COLUMN workflow.reconciliation_run.holding_account_count IS
    'Participant holding accounts read from the ledger this pass -- two per (participant, fund) that has ever been allotted. bigint: a million participants across several funds passes an integer''s range sooner than it looks.';
COMMENT ON COLUMN workflow.reconciliation_run.break_count IS
    'Rows this run wrote to workflow.reconciliation_break. Denormalised deliberately: the gate must not have to count a table that may hold a break per participant.';

-- Serves the gate: "the latest evidence for this date". Ordered by completed_at, not
-- started_at, and NULLS LAST so an unfinished run sorts behind every settled one -- see
-- ReconciliationRepository#latestFor for why the gate reads completion order rather than
-- start order. started_at breaks the tie between two runs that settled in the same
-- microsecond.
CREATE INDEX reconciliation_run_by_date
    ON workflow.reconciliation_run (business_date, completed_at DESC NULLS LAST, started_at DESC);

CREATE TABLE workflow.reconciliation_break (
    run_id                uuid        NOT NULL REFERENCES workflow.reconciliation_run (run_id),
    break_no              integer     NOT NULL,
    scope                 text        NOT NULL,
    fund_id               uuid        REFERENCES reference.fund_identity (fund_id),
    unit_ledger_id        bigint,
    account_id            numeric(39,0),
    left_derivation       text        NOT NULL,
    left_amount_scaled    bigint      NOT NULL,
    right_derivation      text        NOT NULL,
    right_amount_scaled   bigint      NOT NULL,
    drift_scaled          bigint      NOT NULL,
    detected_at           timestamptz NOT NULL,

    CONSTRAINT reconciliation_break_pk
        PRIMARY KEY (run_id, break_no),
    CONSTRAINT reconciliation_break_scope_known
        CHECK (scope IN ('FUND_UNITS', 'CASH_ACCOUNT')),
    CONSTRAINT reconciliation_break_derivations_differ
        CHECK (left_derivation <> right_derivation),
    CONSTRAINT reconciliation_break_derivations_known
        CHECK (left_derivation IN ('LEDGER_PARTICIPANT_UNITS', 'LEDGER_UNITS_IN_ISSUE',
                                   'PROJECTION_UNITS', 'LEDGER_CASH', 'PROJECTION_CASH')
               AND right_derivation IN ('LEDGER_PARTICIPANT_UNITS', 'LEDGER_UNITS_IN_ISSUE',
                                        'PROJECTION_UNITS', 'LEDGER_CASH', 'PROJECTION_CASH')),
    -- A break with no drift is not a break. The service computes the subtraction; this
    -- constraint is what stops a future refactor recording a clean comparison as a finding
    -- and blocking a close that should have passed.
    CONSTRAINT reconciliation_break_drift_non_zero
        CHECK (drift_scaled <> 0),
    CONSTRAINT reconciliation_break_unit_scope_names_a_fund
        CHECK (scope <> 'FUND_UNITS' OR (fund_id IS NOT NULL AND unit_ledger_id IS NOT NULL)),
    CONSTRAINT reconciliation_break_cash_scope_names_an_account
        CHECK (scope <> 'CASH_ACCOUNT' OR account_id IS NOT NULL)
);

COMMENT ON TABLE workflow.reconciliation_break IS
    'One disagreement between two derivations of the same quantity (Task 7.2). Append-only: a break is investigated and aged, never adjusted away, and a participant''s units are never moved to match a projection. The next run either raises it again or does not.';
COMMENT ON COLUMN workflow.reconciliation_break.scope IS
    'FUND_UNITS -- units at 1e-8 for one fund, so the amounts are Units-scaled. CASH_ACCOUNT -- MXN cents for one cash account (ledger 484), so the amounts are Money minor units. The scale is a property of the scope, which is why a single amount column can serve both.';
COMMENT ON COLUMN workflow.reconciliation_break.account_id IS
    'The cash account that disagrees, as AccountId.asUnsignedDecimal() -- the same text form projection.transfer carries a u128 in. Null for a FUND_UNITS break, which names a fund rather than one account.';
COMMENT ON COLUMN workflow.reconciliation_break.drift_scaled IS
    'left_amount_scaled - right_amount_scaled. Signed, and deliberately unclamped: "the ledger holds less than the projection claims" is as real a finding as its opposite (AGENTS.md §4).';

-- Findings are evidence, and this one is structural rather than left to the schema's
-- default privileges: an explicit revoke, plus a trigger that raises for ANY role,
-- including the one migrations run under. reference.fund_nav is guarded exactly this
-- way (V11) and for the same reason -- a control whose findings the operator under
-- pressure can quietly edit is not a control. workflow.allotment carries only the
-- revoke; the difference is deliberate, because a break is the record of a
-- disagreement about participants' money and is the row someone would most want gone.
REVOKE UPDATE, DELETE ON workflow.reconciliation_break FROM fondaria_app;
REVOKE UPDATE, DELETE ON workflow.reconciliation_break FROM PUBLIC;

CREATE FUNCTION workflow.reconciliation_break_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'workflow.reconciliation_break is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER reconciliation_break_no_update_or_delete
    BEFORE UPDATE OR DELETE ON workflow.reconciliation_break
    FOR EACH ROW EXECUTE FUNCTION workflow.reconciliation_break_immutable();

-- ---------------------------------------------------------------------------
-- The candidate set the ledger side of the tie-out is derived from.
-- ---------------------------------------------------------------------------
-- LedgerPort exposes no account enumeration -- deliberately: TigerBeetle is asked
-- for accounts by id, and every id on this platform is derived rather than stored
-- (AccountIdDeriver). So "sum every participant's units" needs a list of
-- (organization, participant, fund) triples to derive holding account ids from, and
-- workflow.allotment is that list: it is written by allotment-job at the moment
-- units are issued, and it is independent of the CDC projection the tie-out is
-- comparing against. Sourcing the triples from projection.participant_units instead
-- would make two of the three derivations share a key set, and a position the
-- projection never recorded would become invisible to the control.
--
-- The existing indexes cannot serve that read. allotment_pk is
-- (organization_id, batch_id, participant_internal_ref, source) -- batch_id sits in
-- the middle, so the distinct triple is not a prefix of it -- and allotment_by_batch
-- and allotment_by_nav_version answer other questions entirely. Without this index
-- each page of the keyset scan re-aggregates the whole table, which is quadratic in
-- the row count and therefore exactly wrong at the scale the close runs at.
CREATE INDEX allotment_by_holding
    ON workflow.allotment (organization_id, participant_internal_ref, fund_id);

COMMENT ON INDEX workflow.allotment_by_holding IS
    'Serves the daily close''s keyset scan for distinct (organization, participant, fund) holdings (Task 7.2). Index-only: the three columns are the whole projection the scan reads.';

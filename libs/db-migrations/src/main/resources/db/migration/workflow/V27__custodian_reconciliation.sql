-- Task 7.3 -- custodian reconciliation: what the external provider reported, and the record of
-- any disagreement with it.
--
-- WHY workflow. ADR-0011: workflow holds service-owned operational state with a status
-- lifecycle. workflow.custodian_statement is what a provider REPORTED -- the same reasoning
-- V12__valuation_batch.sql gives for workflow.valuation: it is not a balance, not a ledger
-- figure, and never summed. workflow.custodian_break is explicitly a lifecycle the task names --
-- "recorded, aged, assigned and investigated" -- which is why it takes workflow's ordinary
-- UPDATE grant rather than reconciliation_break's (V25) append-only shape: that table is a
-- finding re-raised each run with no state of its own, this one is a case an operator works.
--
-- WHY A STATEMENT IS REFUSED RATHER THAN OVERWRITTEN ON CONFLICT. Mirrors
-- JdbcAcceptedValuationRepository: a live statement for a (provider_account_id, business_date)
-- is never silently replaced by a differing one. A resubmission with the same checksum is the
-- same file arriving twice (idempotent, no-op); a resubmission with a different checksum for a
-- date already on record is a conflict the ingestion service refuses rather than resolves --
-- Task 7.3 names no correction/supersession path, so there is none to guess at.
--
-- WHY PENDING-TRADE FIGURES LIVE ON THE STATEMENT. "Timing differences (trades in flight)" are
-- classified from data the custodian itself reports -- real statements carry a
-- settled-position line and a pending/unsettled-trades line -- not by the platform guessing at
-- which of its own ledger transfers the custodian has not caught up to yet. A statement that
-- reports none is pending_trade_*_scaled = 0, and every drift is a real break.

CREATE TABLE workflow.custodian_reconciliation_run (
    run_id                     uuid        NOT NULL,
    business_date              date        NOT NULL,
    status                     text        NOT NULL,
    started_at                 timestamptz NOT NULL,
    completed_at               timestamptz,
    duration_millis            bigint,
    provider_account_count     integer     NOT NULL DEFAULT 0,
    statements_received_count  integer     NOT NULL DEFAULT 0,
    missing_statement_count    integer     NOT NULL DEFAULT 0,
    real_break_count           integer     NOT NULL DEFAULT 0,
    timing_difference_count    integer     NOT NULL DEFAULT 0,

    CONSTRAINT custodian_reconciliation_run_pk
        PRIMARY KEY (run_id),
    CONSTRAINT custodian_reconciliation_run_status_known
        CHECK (status IN ('RUNNING', 'PASSED', 'FAILED')),
    CONSTRAINT custodian_reconciliation_run_settled_when_complete
        CHECK ((status = 'RUNNING') = (completed_at IS NULL)),
    CONSTRAINT custodian_reconciliation_run_counts_not_negative
        CHECK (provider_account_count >= 0 AND statements_received_count >= 0
               AND missing_statement_count >= 0 AND real_break_count >= 0
               AND timing_difference_count >= 0)
);

COMMENT ON TABLE workflow.custodian_reconciliation_run IS
    'One custodian-reconciliation pass (Task 7.3): every ProviderAccount active on a business date, tied against its statement. The gate Task 7.5''s sign-off reads. Same lifecycle shape as workflow.reconciliation_run (V25) -- RUNNING opened before any comparison, so a process that dies leaves a row that is visibly not a pass.';
COMMENT ON COLUMN workflow.custodian_reconciliation_run.status IS
    'FAILED if any provider account is missing its statement for this date, or if a real (non-timing) break is open. A timing difference alone does not fail the run -- it is expected, tracked in workflow.custodian_break, and does not block the gate.';
COMMENT ON COLUMN workflow.custodian_reconciliation_run.missing_statement_count IS
    'Active ProviderAccounts with no workflow.custodian_statement row for this business_date when the pass ran. The pass itself is the deadline -- there is no separate lateness grace period.';

CREATE INDEX custodian_reconciliation_run_by_date
    ON workflow.custodian_reconciliation_run (business_date, completed_at DESC NULLS LAST, started_at DESC);

CREATE TABLE workflow.custodian_statement (
    statement_id                 uuid        NOT NULL,
    provider_account_id          uuid        NOT NULL REFERENCES reference.provider_account (provider_account_id),
    business_date                date        NOT NULL,
    reported_units_scaled        bigint      NOT NULL,
    reported_cash_cents          bigint      NOT NULL,
    pending_trade_units_scaled   bigint      NOT NULL DEFAULT 0,
    pending_trade_cash_cents     bigint      NOT NULL DEFAULT 0,
    source                       text        NOT NULL,
    checksum_sha256              text        NOT NULL,
    received_at                  timestamptz NOT NULL,

    CONSTRAINT custodian_statement_pk
        PRIMARY KEY (statement_id),
    CONSTRAINT custodian_statement_one_per_account_and_date
        UNIQUE (provider_account_id, business_date),
    CONSTRAINT custodian_statement_reported_units_non_negative
        CHECK (reported_units_scaled >= 0),
    CONSTRAINT custodian_statement_reported_cash_non_negative
        CHECK (reported_cash_cents >= 0)
);

COMMENT ON TABLE workflow.custodian_statement IS
    'One provider''s daily position-and-cash statement for one ProviderAccount (Task 7.3). What the counterparty reported -- the truth this platform does not control, never a ledger figure and never summed into one.';
COMMENT ON COLUMN workflow.custodian_statement.reported_units_scaled IS
    'Units at 1e-8 the provider reports settled and held for this account. Compared against LedgerPort''s resolution of the account''s scope (units_in_issue for ALL_PARTICIPANTS, summed holdings for ORGANIZATION).';
COMMENT ON COLUMN workflow.custodian_statement.reported_cash_cents IS
    'MXN cents the provider reports settled and held for this account''s fund. Compared against the fund''s single FUND_CASH ledger balance, regardless of the account''s scope (ADR-0019).';
COMMENT ON COLUMN workflow.custodian_statement.pending_trade_units_scaled IS
    'Signed units, at 1e-8, the provider reports as traded but not yet settled into the position above. A drift this exactly explains is a timing difference, not a break.';
COMMENT ON COLUMN workflow.custodian_statement.pending_trade_cash_cents IS
    'Signed MXN cents, the cash-side counterpart of pending_trade_units_scaled.';

CREATE INDEX custodian_statement_by_account_and_date
    ON workflow.custodian_statement (provider_account_id, business_date DESC);

CREATE TABLE workflow.custodian_break (
    break_id                     uuid        NOT NULL,
    provider_account_id          uuid        NOT NULL REFERENCES reference.provider_account (provider_account_id),
    category                     text        NOT NULL,
    status                       text        NOT NULL,
    drift_scaled                 bigint      NOT NULL,
    first_detected_business_date date        NOT NULL,
    last_confirmed_business_date date        NOT NULL,
    assigned_to                  text,
    opened_at                    timestamptz NOT NULL,
    status_updated_at            timestamptz NOT NULL,
    escalated_at                 timestamptz,
    resolved_at                  timestamptz,
    resolution_note              text,

    CONSTRAINT custodian_break_pk
        PRIMARY KEY (break_id),
    CONSTRAINT custodian_break_category_known
        CHECK (category IN ('UNIT_BREAK', 'CASH_BREAK', 'UNIT_TIMING_DIFFERENCE', 'CASH_TIMING_DIFFERENCE')),
    CONSTRAINT custodian_break_status_known
        CHECK (status IN ('OPEN', 'ASSIGNED', 'INVESTIGATING', 'RESOLVED', 'ESCALATED')),
    CONSTRAINT custodian_break_drift_non_zero
        CHECK (drift_scaled <> 0),
    CONSTRAINT custodian_break_dates_ordered
        CHECK (last_confirmed_business_date >= first_detected_business_date),
    CONSTRAINT custodian_break_resolved_has_timestamp
        CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL))
);

COMMENT ON TABLE workflow.custodian_break IS
    'One disagreement between a ProviderAccount''s statement and this platform''s own figures (Task 7.3): recorded once, aged across runs, assigned and investigated. Never adjusted to make participant units or cash match the custodian -- that is a rule enforced by never wiring eod-job to a TransferIdDeriver (ADR-0018), not by this table.';
COMMENT ON COLUMN workflow.custodian_break.category IS
    'UNIT_BREAK / CASH_BREAK -- an unexplained disagreement, escalates on the real-break threshold. UNIT_TIMING_DIFFERENCE / CASH_TIMING_DIFFERENCE -- fully explained by the statement''s own pending-trade figure, expected rather than an error, and ages on its own, longer threshold.';
COMMENT ON COLUMN workflow.custodian_break.drift_scaled IS
    'Ledger figure minus provider-reported figure (minus the explained pending-trade amount, for a timing difference), signed and unclamped (docs/ENGINEERING-RULES.md Sec 4): the ledger holding less is exactly as real a finding as holding more.';
COMMENT ON COLUMN workflow.custodian_break.first_detected_business_date IS
    'When this break was first opened. Age is first_detected_business_date to the current business date, computed at read time rather than stored, so it is never stale between runs.';
COMMENT ON COLUMN workflow.custodian_break.last_confirmed_business_date IS
    'The most recent business date a run still found this break present. Updated in place on a re-observation; the break row itself is not re-created day over day the way workflow.reconciliation_break is, because this one has a status an operator changes.';

-- At most one open (non-terminal) break per account and category: a break is opened once and
-- then re-observed, never duplicated into a second row for the same still-live condition.
CREATE UNIQUE INDEX custodian_break_open_per_account_and_category
    ON workflow.custodian_break (provider_account_id, category)
    WHERE status <> 'RESOLVED';

-- Serves the ageing sweep: every non-terminal break, oldest first, to compare against its
-- category's escalation threshold.
CREATE INDEX custodian_break_open_by_age
    ON workflow.custodian_break (status, category, first_detected_business_date)
    WHERE status <> 'RESOLVED';

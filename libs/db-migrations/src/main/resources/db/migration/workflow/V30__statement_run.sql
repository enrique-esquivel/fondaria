-- Task 7.5b -- the statement worker pool's own durable record: one row per calendar
-- month's fan-out, in the shape ADR-0022 Section 3 pins down.
--
-- WHY workflow AND NOT reference. Same reasoning as V28 (snapshot_run) and V29
-- (close_signoff): a statement run is a service-owned status lifecycle
-- (RUNNING -> COMPLETED | FAILED), not effective-dated reference data, and nothing
-- resolves it as-of a date -- it is looked up by the exact period it fans out.
--
-- WHY THIS ROW AND NOT JUST THE REDIS COUNTER. ADR-0022 Section 3 is explicit: the
-- counter is a live progress signal an operator reads while a run is in flight, not
-- an audit record -- Redis is not durable across a restart of that store, and it
-- is never consulted to answer "did August's statements go out". This table is
-- that answer: expected_count is the control total (workflow.snapshot_run's own
-- position_count for the month-end date, copied onto close.signed-off.v1), and
-- completed_count is the counter's value copied back once the two agree.
--
-- WHY THE SCHEDULER ALWAYS PUBLISHES, REGARDLESS OF WHAT THIS ROW SAYS. Unlike
-- close_signoff's "ON CONFLICT DO NOTHING" guard, this table is never a gate over
-- whether the fan-out happens: it is written for observability only. Publishing
-- statement.jobs.v1 a second time for a period is safe -- render is a pure function
-- of stored data (rerun produces an identical hash) and the Redis completion
-- counter is idempotent per participant -- so nothing here may ever cause a gap by
-- skipping a re-publish. See StatementScheduler.
--
-- NO nav_version_id, NO per-participant row. A statement is not evidence in the
-- sense workflow.allotment or reference.fund_nav are -- it is a rendering of data
-- those two tables already hold, byte-reproducible from them at any time. Storing
-- 250,000 rows a month to duplicate that would be the second implementation
-- AGENTS.md Section 4 warns against, and buys no auditability the object itself
-- and its deterministic key do not already provide.

CREATE TABLE workflow.statement_run (
    period                       text        NOT NULL,
    close_business_date          date        NOT NULL,
    status                       text        NOT NULL,
    started_at                   timestamptz NOT NULL,
    completed_at                 timestamptz,
    duration_millis              bigint,
    expected_count               bigint      NOT NULL,
    completed_count              bigint,
    failure_reason               text,

    CONSTRAINT statement_run_pk
        PRIMARY KEY (period),
    CONSTRAINT statement_run_period_is_year_month
        CHECK (period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT statement_run_status_known
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT statement_run_settled_when_complete
        CHECK ((status = 'RUNNING') = (completed_at IS NULL)),
    CONSTRAINT statement_run_counts_not_negative
        CHECK (expected_count >= 0 AND (completed_count IS NULL OR completed_count >= 0))
);

COMMENT ON TABLE workflow.statement_run IS
    'One row per calendar month''s statement fan-out (Task 7.5b, ADR-0022 Section 3): the durable record of expected_count (the control total, from workflow.snapshot_run.position_count) and completed_count (the Redis counter''s value, copied back on settlement). Never a gate over whether the fan-out runs -- see StatementScheduler.';
COMMENT ON COLUMN workflow.statement_run.period IS
    'Calendar month the statements cover, YYYY-MM. Primary key: one run of record per month, matching one ParticipantStatement period (contracts/openapi/balance-api.yaml).';
COMMENT ON COLUMN workflow.statement_run.close_business_date IS
    'The last business day of the month, from BusinessCalendar -- the close.signed-off.v1 event that triggered this run. Never the wall clock.';
COMMENT ON COLUMN workflow.statement_run.status IS
    'RUNNING -> COMPLETED | FAILED. Written RUNNING when the scheduler dispatches the fan-out, and settled by a periodic sweep comparing the Redis counter to expected_count -- see StatementRunSettler.';
COMMENT ON COLUMN workflow.statement_run.expected_count IS
    'workflow.snapshot_run.position_count for the month-end snapshot, carried on close.signed-off.v1 -- the exact number of statements this run must produce (Task 7.5b acceptance criterion).';
COMMENT ON COLUMN workflow.statement_run.completed_count IS
    'The Redis completion counter''s value at settlement. Null while RUNNING: the live count lives in Redis, not here, because 250,000 increments a month have no business contending with the projection writers for a number nobody queries transactionally (ADR-0022 Section 3).';
COMMENT ON COLUMN workflow.statement_run.failure_reason IS
    'Why a FAILED run failed, in operator-facing words. Never an exception message or a hostname (AGENTS.md Section 7).';

-- The scheduler's own idempotent write ("has this period already been opened") and
-- the settlement sweep's read ("every RUNNING period") are both single-row or
-- tiny-scan lookups already served by the primary key; no secondary index earns
-- its keep at one row inserted per month.

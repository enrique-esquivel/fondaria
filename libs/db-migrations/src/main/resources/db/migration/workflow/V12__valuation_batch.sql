-- Task 20.3 — valuation ingestion (ADR-0009 transport, ADR-0011 placement).
--
-- V11 is deliberately skipped: phase-20-fund-accounting-nav.md Task 20.4 names
-- `db/migration/reference/V11__fund_nav.sql` explicitly, and the version sequence
-- is global across the platform (see libs/db-migrations/pom.xml). Taking V11 here
-- would force that task to renumber a file its own specification names.
--
-- WHY BOTH TABLES ARE IN `workflow` AND NEITHER IS THE NAV.
-- ADR-0011 puts service-owned operational state with a status lifecycle in
-- `workflow`, and that is exactly what ingestion is: a file arrives, is claimed,
-- is validated, and lands ACCEPTED or REJECTED. The NAV struck from an accepted
-- valuation is a different fact with different rules -- append-only, versioned,
-- supersedable, rule-evaluation evidence -- and it lives in `reference.fund_nav`,
-- built by Task 20.4. Nothing here is a price.
--
-- `workflow.valuation` is what an external provider REPORTED. It is not a
-- balance, not a NAV, and never summed. Task 20.4 reads one row of it, reads
-- units in issue from the ledger through LedgerPort, and divides -- and it is
-- that division, with the units in issue it used, that gets persisted as a NAV.

CREATE TABLE workflow.valuation_batch (
    batch_id            uuid        NOT NULL,
    source              text        NOT NULL,
    object_key          text        NOT NULL,
    checksum_sha256     text        NOT NULL,
    status              text        NOT NULL,

    declared_row_count  integer     NOT NULL,

    -- How many valuations this batch actually recorded. NULL until ACCEPTED, and
    -- legitimately lower than declared_row_count: a row that redelivers a
    -- valuation already accepted, byte-identical, is an idempotent no-op rather
    -- than a second valuation (valuation-file-v1.md §4, check 9).
    accepted_valuation_count integer,

    rejection_code      text,
    rejection_message   text,
    rejection_details   jsonb,

    -- When the current validation attempt claimed this batch. A process that dies
    -- mid-validation leaves a VALIDATING row whose lease has stopped advancing,
    -- and the poller re-claims it once it goes stale -- the same lease-in-a-column
    -- pattern workflow.contribution_batch uses (V4), and for the same reason:
    -- in-JVM state could not be recovered by another instance.
    validation_started_at timestamptz,

    submitted_at        timestamptz NOT NULL,
    status_updated_at   timestamptz NOT NULL,

    CONSTRAINT valuation_batch_pk
        PRIMARY KEY (batch_id),

    -- Submission is content-addressed, reusing ADR-0009 unchanged: a batch IS
    -- (source, objectKey, checksumSha256), and batch_id is derived from those
    -- three values. Providers redeliver files routinely, so this is not an edge
    -- case -- it is the normal path, and it must converge on one batch rather than
    -- accumulate one per delivery. A concurrent resubmission collides here and on
    -- the primary key alike, which is why the insert carries no inference
    -- specification (see JdbcValuationBatchRepository).
    CONSTRAINT valuation_batch_content_address_unique
        UNIQUE (source, object_key, checksum_sha256),

    CONSTRAINT valuation_batch_status_known
        CHECK (status IN ('RECEIVED', 'VALIDATING', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT valuation_batch_declared_row_count_positive
        CHECK (declared_row_count > 0),
    CONSTRAINT valuation_batch_accepted_count_non_negative
        CHECK (accepted_valuation_count IS NULL OR accepted_valuation_count >= 0),
    CONSTRAINT valuation_batch_source_present
        CHECK (length(btrim(source)) > 0)
);

COMMENT ON TABLE workflow.valuation_batch IS
    'One delivered valuation file and its validation lifecycle (Task 20.3). Content-addressed on (source, object_key, checksum_sha256), so redelivering a file is the same batch.';
COMMENT ON COLUMN workflow.valuation_batch.accepted_valuation_count IS
    'Valuations recorded by this batch. Lower than declared_row_count when rows were identical redeliveries, which are no-ops rather than new valuations.';

-- The poller's claim predicate. Partial, because RECEIVED and VALIDATING rows are
-- a vanishing fraction of the table once a year of daily files has accumulated,
-- and every sweep runs this query.
CREATE INDEX valuation_batch_claimable_idx
    ON workflow.valuation_batch (submitted_at)
    WHERE status IN ('RECEIVED', 'VALIDATING');


CREATE TABLE workflow.valuation (
    valuation_id              uuid        NOT NULL,
    batch_id                  uuid        NOT NULL REFERENCES workflow.valuation_batch (batch_id),

    -- No foreign key to reference.fund_identity, deliberately. A fund's existence
    -- is checked in code, resolved as of the row's OWN valuation_date (Task 20.1
    -- funds are effective-dated), and a UNKNOWN_FUND rejection has to name the
    -- fund and date at fault. A foreign-key violation surfaces as a driver
    -- exception carrying a constraint name, which AGENTS.md §7 forbids rendering
    -- and which could not carry that detail anyway.
    fund_id                   uuid        NOT NULL,
    valuation_date            date        NOT NULL,

    -- What the provider reported the pooled assets are worth, in MXN cents.
    -- bigint, never numeric or double (AGENTS.md §4). A pooled balance is not
    -- negative, hence the CHECK -- but note that the NAV struck from this is free
    -- to FALL, and nothing downstream clamps it.
    reported_value_minor_units bigint     NOT NULL,

    provider_reference        text        NOT NULL,
    received_at               timestamptz NOT NULL,

    CONSTRAINT valuation_pk
        PRIMARY KEY (valuation_id),

    -- One accepted valuation per fund per date. This is what makes
    -- "a valuation for a date already live is not an overwrite" structural: the
    -- second delivery either carries the identical amount and is skipped, or
    -- carries a different one and is refused as VALUATION_ALREADY_LIVE so it has
    -- to go through Task 20.5's correction path, where it needs a reason and an
    -- audit trail. Neither branch ever UPDATEs this row.
    CONSTRAINT valuation_fund_date_unique
        UNIQUE (fund_id, valuation_date),

    CONSTRAINT valuation_reported_value_non_negative
        CHECK (reported_value_minor_units >= 0),
    CONSTRAINT valuation_provider_reference_present
        CHECK (length(btrim(provider_reference)) > 0)
);

COMMENT ON TABLE workflow.valuation IS
    'What an external provider reported a fund''s pooled assets were worth on a valuation date (Task 20.3). Never a NAV, never a balance, never summed. Task 20.4 divides one of these rows by units in issue read from the ledger to strike a NAV.';
COMMENT ON COLUMN workflow.valuation.reported_value_minor_units IS
    'MXN cents, integer. The NAV struck from it may fall; nothing clamps it (AGENTS.md §4).';
COMMENT ON COLUMN workflow.valuation.provider_reference IS
    'The provider''s own identifier for this valuation, carried so a number in the platform traces back to the statement it came from. Never parsed, and never compared when deciding whether a redelivery contradicts an accepted valuation.';

-- The ageing report walks business dates per fund looking for absences, and
-- Task 20.4's strike looks a valuation up by exactly this pair.
CREATE INDEX valuation_fund_date_idx
    ON workflow.valuation (fund_id, valuation_date);

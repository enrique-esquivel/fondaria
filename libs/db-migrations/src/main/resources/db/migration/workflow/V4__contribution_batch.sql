-- Task 3.4 — the contribution batch, and the cash it claims (ADR-0009, ADR-0011).
--
-- ADR-0011 puts both tables in `workflow`: service-owned operational state with a
-- status lifecycle, written directly by contribution-api, produced by no CDC
-- stream.
--
-- WHAT THESE TABLES ARE NOT.
-- `declared_total_minor_units` is what the employer's file DECLARED, copied out of
-- the file header so the reconciliation gate can be re-evaluated without re-parsing
-- a hundred-thousand-row file. It is not a balance and not a receipt. The money an
-- organization actually holds is read from EMPLOYER_COLLECTIONS through LedgerPort,
-- and every amount the gate adds up comes from LedgerPort.lookupTransfer — never
-- from a column here and never from workflow.cash_receipt.amount_minor_units, which
-- records only what an operator asserted a wire was (ADR-0008 invariant 1, restated
-- by ADR-0011).
--
-- What PostgreSQL does own is the ALLOCATION: which receipts exist for an
-- organization and which are already spoken for. TigerBeetle has no concept of a
-- wire being consumed by a batch, so that fact has to live here.

CREATE TABLE workflow.contribution_batch (
    batch_id                   uuid        NOT NULL,
    organization_id            uuid        NOT NULL REFERENCES master.organization (organization_id),
    object_key                 text        NOT NULL,
    checksum_sha256            text        NOT NULL,
    status                     text        NOT NULL,

    -- Populated from the file header once the file parses; NULL before that.
    -- ADR-0009: the file is the single declared source of truth for its own
    -- content, so none of these arrive on the API request.
    file_format_version        integer,
    plan_id                    text,
    payroll_period             text,
    value_date                 date,
    declared_total_minor_units bigint,
    declared_row_count         integer,

    rejection_code             text,
    rejection_message          text,
    rejection_details          jsonb,

    -- When the current validation attempt claimed this batch. A process that dies
    -- mid-validation leaves a VALIDATING row whose lease has stopped advancing, and
    -- the poller re-claims it once it goes stale. In-JVM state could not be
    -- recovered by another instance; a column can.
    validation_started_at      timestamptz,

    submitted_at               timestamptz NOT NULL,
    status_updated_at          timestamptz NOT NULL,

    CONSTRAINT contribution_batch_pk
        PRIMARY KEY (batch_id),

    -- Submission is content-addressed (ADR-0009 as amended): a batch IS
    -- (organizationId, objectKey, checksumSha256), so resubmitting the same file is
    -- the same batch. There is no Idempotency-Key header on this endpoint and no
    -- 422 for "same key, different body" — with the checksum as the key, that state
    -- cannot be expressed. This constraint is what makes that structural rather
    -- than merely intended, and batch_id is derived from the same three values, so
    -- a concurrent resubmission collides here and on the primary key alike.
    CONSTRAINT contribution_batch_content_address_unique
        UNIQUE (organization_id, object_key, checksum_sha256),

    CONSTRAINT contribution_batch_checksum_lower_hex
        CHECK (checksum_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT contribution_batch_object_key_present
        CHECK (length(btrim(object_key)) > 0),
    CONSTRAINT contribution_batch_status_known
        CHECK (status IN ('RECEIVED', 'VALIDATING', 'VALIDATED', 'PENDING_RECONCILIATION', 'REJECTED')),

    -- A rejection reason belongs to a rejection and to nothing else, so a VALIDATED
    -- batch can never carry a stale explanation of a failure that was later
    -- repaired. Same shape as workflow.cash_receipt's.
    CONSTRAINT contribution_batch_rejection_matches_status
        CHECK ((status = 'REJECTED') = (rejection_code IS NOT NULL)),
    CONSTRAINT contribution_batch_rejection_message_with_code
        CHECK ((rejection_code IS NULL) = (rejection_message IS NULL)),

    -- Whole-batch rejection codes, contribution-file-v1.md §5. Per-row plan-rule
    -- reasons are Task 3.5's contract and are not this column's vocabulary.
    CONSTRAINT contribution_batch_rejection_code_known
        CHECK (rejection_code IS NULL OR rejection_code IN (
            'CHECKSUM_MISMATCH', 'MALFORMED_FILE', 'ORGANIZATION_MISMATCH',
            'ROW_COUNT_MISMATCH', 'DUPLICATE_ROW', 'SOURCE_SUBTOTAL_MISMATCH',
            'DECLARED_TOTAL_MISMATCH')),

    -- A declared total is money the employer says it sent. Zero or negative is not
    -- a batch: contribution-file-v1.md §2 makes every amount a non-negative
    -- integer, and a file with nothing to contribute has no rows and is malformed
    -- before it ever reaches this table.
    CONSTRAINT contribution_batch_declared_total_positive
        CHECK (declared_total_minor_units IS NULL OR declared_total_minor_units > 0),
    CONSTRAINT contribution_batch_declared_row_count_positive
        CHECK (declared_row_count IS NULL OR declared_row_count > 0),

    -- Past structural validation the header fields are all known or all unknown;
    -- a batch cannot be reconciled against a total it never parsed.
    CONSTRAINT contribution_batch_header_all_or_nothing
        CHECK ((declared_total_minor_units IS NULL) = (value_date IS NULL)
           AND (declared_total_minor_units IS NULL) = (plan_id IS NULL)),
    CONSTRAINT contribution_batch_reconcilable_has_total
        CHECK (status NOT IN ('VALIDATED', 'PENDING_RECONCILIATION')
               OR declared_total_minor_units IS NOT NULL)
);

COMMENT ON TABLE workflow.contribution_batch IS
    'One submitted dispersion file and where its validation stands (ADR-0009). Identified by (organization_id, object_key, checksum_sha256) rather than by an idempotency header: the checksum is the key.';
COMMENT ON COLUMN workflow.contribution_batch.batch_id IS
    'UUIDv5(organizationId, objectKey + 0x1f + checksumSha256) — derived, not generated, so two concurrent submissions of one file converge on one id rather than racing to create two. Scoped under the organization''s own id, so no batch id can collide across tenants and no third derivation namespace has to be configured and kept forever.';
COMMENT ON COLUMN workflow.contribution_batch.declared_total_minor_units IS
    'MXN cents, from the file header. bigint, never numeric: cash is a long in cents everywhere (docs/ENGINEERING-RULES.md §4). What the employer DECLARED — the reconciliation gate compares it against money read from the ledger, and this column is never one side of a balance.';
COMMENT ON COLUMN workflow.contribution_batch.status IS
    'RECEIVED -> VALIDATING -> VALIDATED | PENDING_RECONCILIATION | REJECTED. PENDING_RECONCILIATION is not terminal: the file is structurally sound and its cash has not arrived, so the poller re-runs the gate alone (the header fields above are stored precisely so it need not re-parse the file).';
COMMENT ON COLUMN workflow.contribution_batch.validation_started_at IS
    'Lease timestamp for the current validation attempt. A batch left VALIDATING by a dead process is re-claimed once this goes stale; the lease is a row, not memory, so any instance can recover it.';

-- The poller sweeps by status, oldest first.
CREATE INDEX contribution_batch_by_status_submitted
    ON workflow.contribution_batch (status, submitted_at);

-- The ageing report sweeps one organization's outstanding files.
CREATE INDEX contribution_batch_by_organization_status
    ON workflow.contribution_batch (organization_id, status);


-- Which cash receipts a batch has consumed.
--
-- THIS TABLE IS THE ANSWER TO "WHOSE MONEY IS THIS". EMPLOYER_COLLECTIONS[org] is a
-- running balance across every batch that organization has ever sent, so it cannot
-- say how much cash belongs to one batch; and summing workflow.cash_receipt is
-- forbidden. So the allocation is recorded explicitly, one row per consumed receipt,
-- and the primary key below is what makes "two batches consumed the same wire"
-- impossible at the database rather than merely unlikely in the service.
CREATE TABLE workflow.contribution_batch_cash_claim (
    organization_id uuid        NOT NULL,
    transfer_id     uuid        NOT NULL REFERENCES workflow.cash_receipt (transfer_id),
    batch_id        uuid        NOT NULL REFERENCES workflow.contribution_batch (batch_id),
    claimed_at      timestamptz NOT NULL,

    -- Tenant first (docs/ENGINEERING-RULES.md §4), and one receipt is claimed at most once. The
    -- foreign key above already scopes transfer_id to a single receipt, so this is
    -- the constraint that does the work: a second batch inserting the same
    -- transfer_id fails, and its whole claim rolls back rather than half-taking
    -- another batch's money.
    CONSTRAINT contribution_batch_cash_claim_pk
        PRIMARY KEY (organization_id, transfer_id)
);

COMMENT ON TABLE workflow.contribution_batch_cash_claim IS
    'Allocation of reconciled cash receipts to contribution batches. PostgreSQL owns which receipts are spoken for; the ledger owns how much each one was. No amount is stored here — re-reading it from LedgerPort.lookupTransfer is what keeps the gate honest about money that did not actually move.';
COMMENT ON COLUMN workflow.contribution_batch_cash_claim.transfer_id IS
    'The CASH_RECEIPT transfer this batch consumed. The amount is deliberately absent: copying it here would create a second, staler answer to a question TigerBeetle already answers correctly.';

CREATE INDEX contribution_batch_cash_claim_by_batch
    ON workflow.contribution_batch_cash_claim (batch_id);

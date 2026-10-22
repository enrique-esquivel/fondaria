-- Task 3.3 — the cash receipt an operator keys in (ADR-0010).
--
-- ADR-0011 puts this in `workflow`: it is service-owned operational state with
-- a status lifecycle, written directly by contribution-api, and no CDC stream
-- produces it. It is emphatically NOT a balance. What money the organization
-- holds is read from EMPLOYER_COLLECTIONS through LedgerPort and never summed
-- from this table; `amount_minor_units` here records what an operator asserted
-- a single wire was, not what the employer has.
--
-- WRITE ORDER, AND WHY THIS TABLE EXISTS AT ALL (ADR-0011).
-- The row is committed FIRST, with status UNCONFIRMED, and only then is the
-- CASH_RECEIPT transfer posted; the status moves to POSTED afterwards. A crash
-- between the two therefore leaves a row saying "an operator asserted this wire
-- exists and the movement is unconfirmed" — reconcilable, and repaired by any
-- retry, because both writes are idempotent on keys derived from
-- (organization_id, idempotency_key). The reverse order would leave money moved
-- with nothing recording why, which is the defect ADR-0011 exists to close.

CREATE TABLE workflow.cash_receipt (
    organization_id     uuid        NOT NULL REFERENCES master.organization (organization_id),
    idempotency_key     text        NOT NULL,
    transfer_id         uuid        NOT NULL,
    amount_minor_units  bigint      NOT NULL,
    currency            text        NOT NULL,
    value_date          date        NOT NULL,
    bank_reference      text        NOT NULL,
    operator_id         text        NOT NULL,
    request_fingerprint text        NOT NULL,
    status              text        NOT NULL,
    rejection_reason    text,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,

    -- The idempotency key is scoped to the organization, exactly as the derived
    -- transfer id is: TransferIdDeriver.derive(organizationId, idempotencyKey).
    -- One employer's key can therefore never collide with, or be guessed from,
    -- another's.
    CONSTRAINT cash_receipt_pk
        PRIMARY KEY (organization_id, idempotency_key),

    -- The ledger is the idempotency authority (ADR-0010, docs/DESIGN-DECISIONS.md), and
    -- this says the same thing from the PostgreSQL side: one derived transfer id
    -- can never be claimed by two receipts.
    CONSTRAINT cash_receipt_transfer_id_unique
        UNIQUE (transfer_id),

    -- Money arriving is money arriving. A zero or negative receipt is not a
    -- refund and not a correction — a correction is a CORRECTION transfer
    -- (chart-of-accounts.md §4), never a negative receipt or an UPDATE here.
    CONSTRAINT cash_receipt_amount_positive
        CHECK (amount_minor_units > 0),
    CONSTRAINT cash_receipt_currency_mxn
        CHECK (currency = 'MXN'),
    CONSTRAINT cash_receipt_bank_reference_present
        CHECK (length(btrim(bank_reference)) > 0),
    CONSTRAINT cash_receipt_operator_present
        CHECK (length(btrim(operator_id)) > 0),
    CONSTRAINT cash_receipt_status_known
        CHECK (status IN ('UNCONFIRMED', 'POSTED', 'REJECTED')),
    -- A reason belongs to a rejection and to nothing else, so a POSTED row can
    -- never carry a stale explanation of a failure that was later repaired.
    CONSTRAINT cash_receipt_reason_matches_status
        CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL))
);

COMMENT ON TABLE workflow.cash_receipt IS
    'One manually reconciled employer wire (ADR-0010). Written before the ledger transfer and moved to POSTED after it, so a crash between the two leaves a reconcilable row rather than money moved with nothing recording why (ADR-0011).';
COMMENT ON COLUMN workflow.cash_receipt.transfer_id IS
    'UUIDv5(ledgerNamespace, organizationId + ":" + idempotencyKey) — the CASH_RECEIPT transfer id, derived, never generated in a request path. Recorded here so a row can be tied to its ledger movement without re-deriving it.';
COMMENT ON COLUMN workflow.cash_receipt.amount_minor_units IS
    'MXN cents. bigint, never numeric or decimal: cash is a long in cents everywhere in this platform (AGENTS.md §4). This is one asserted wire, never a balance.';
COMMENT ON COLUMN workflow.cash_receipt.bank_reference IS
    'The statement line this receipt claims to be. Lives here rather than on the transfer because the ledger holds money and links, never descriptive data (ADR-0011); the transfer carries only ExternalReference.derive(ledgerNamespace, bankReference), which ties the two together by recomputation.';
COMMENT ON COLUMN workflow.cash_receipt.operator_id IS
    'Provenance of the assertion: who or what claimed this wire exists. Today an ops operator keying it from a bank statement, and self-asserted rather than authenticated — Phase 9 replaces it with the authenticated subject, and O-36''s bank feed replaces it with the statement matcher. Not a signature: see docs/01-DECISIONS-TO-DISCUSS.md G16.';
COMMENT ON COLUMN workflow.cash_receipt.request_fingerprint IS
    'SHA-256 over the canonical request (CashReceiptFingerprint). Answers "same idempotency key, different request" with a 422 without consulting Redis, so that check survives a cold cache. Versioned canonical form, so a future change is detectable rather than silently re-derived.';
COMMENT ON COLUMN workflow.cash_receipt.status IS
    'UNCONFIRMED -> POSTED on a successful ledger post; UNCONFIRMED -> REJECTED when the ledger refuses it. UNCONFIRMED is also what a crash mid-post leaves, and any retry converges from it.';

-- Every read is by (organization_id, idempotency_key), which the primary key
-- serves. This index serves the other direction — reconciliation against a bank
-- statement, and the ageing report of Task 3.4, both of which sweep one
-- organization's receipts by value date.
CREATE INDEX cash_receipt_by_organization_value_date
    ON workflow.cash_receipt (organization_id, value_date);

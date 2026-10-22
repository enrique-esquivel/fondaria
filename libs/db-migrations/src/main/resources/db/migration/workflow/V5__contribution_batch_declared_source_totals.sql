-- Contribution file format v2 (contracts/files/contribution-file-v2.md §1.1) moves a
-- batch's declarations out of the file and into the submission call. Two consequences
-- for this table.
--
-- 1. THE PER-SOURCE DECLARED TOTALS HAVE TO BE STORED. v1 carried them as `S` records
--    inside the file, so the validator read them from the bytes it was already parsing.
--    They now arrive on the request, and validation runs later, in a poller that reads
--    this row and never sees the request. Unstored, SOURCE_SUBTOTAL_MISMATCH could not
--    be evaluated at all -- and that check is what keeps employee and employer money
--    separately represented end to end (Task 3.1).
--
--    One column per source rather than a jsonb map: these are money, and money gets a
--    typed column with a CHECK on it. Adding a source already costs a format version and
--    a column in the file contract; a column here is the same class of change.
--
-- 2. Every declaration is now known at INSERT, not after the file parses. The columns
--    stay nullable, deliberately: making them NOT NULL would break the previous
--    application version, which writes them as NULL until validation (Flyway
--    expand/contract, docs/ENGINEERING-RULES.md §4). Tightening them is a later migration, once no
--    version that writes NULL is deployed.
--
-- V4 is merged and is never edited, so the widened constraints are replaced here.

-- 3. WHETHER THE FILE HAS BEEN READ NEEDS ITS OWN COLUMN. Under v1 that fact was
--    implied: the header fields were NULL until the file parsed, so "header present"
--    meant "already read". With the declarations arriving at submission there is nothing
--    left to imply it -- and it cannot be inferred from `status`, because claiming a
--    batch for a sweep moves it to VALIDATING first. A PENDING_RECONCILIATION batch that
--    was re-read on every sweep would re-fetch and re-parse a hundred-thousand-row file
--    every few seconds for as long as an employer's wire is outstanding, which is exactly
--    what Task 3.4 stored the header to avoid.

ALTER TABLE workflow.contribution_batch
    ADD COLUMN file_validated_at              timestamptz,
    ADD COLUMN declared_employee_minor_units  bigint,
    ADD COLUMN declared_employer_minor_units  bigint,
    ADD COLUMN declared_voluntary_minor_units bigint;

-- Signed is not wanted here and never will be: a declared contribution total is money an
-- employer says it is sending. A negative one is a malformed submission, not a refund --
-- money leaving is a withdrawal, which is its own movement with its own transfer code.
ALTER TABLE workflow.contribution_batch
    ADD CONSTRAINT contribution_batch_declared_source_totals_non_negative
        CHECK ((declared_employee_minor_units  IS NULL OR declared_employee_minor_units  >= 0)
           AND (declared_employer_minor_units  IS NULL OR declared_employer_minor_units  >= 0)
           AND (declared_voluntary_minor_units IS NULL OR declared_voluntary_minor_units >= 0));

-- The three source totals are present exactly when the grand total is, so a row can never
-- hold a grand total whose composition is unknown -- the same all-or-nothing rule V4
-- applies to the rest of the declaration.
ALTER TABLE workflow.contribution_batch
    ADD CONSTRAINT contribution_batch_declared_source_totals_all_or_nothing
        CHECK ((declared_total_minor_units IS NULL) = (declared_employee_minor_units  IS NULL)
           AND (declared_total_minor_units IS NULL) = (declared_employer_minor_units  IS NULL)
           AND (declared_total_minor_units IS NULL) = (declared_voluntary_minor_units IS NULL));

-- v2 files carry no format version of their own -- the file is a plain CSV whose shape is
-- the column-header row, and the version that matters is the API contract's. The column
-- stays for the rows that recorded one; nothing writes it from now on.
COMMENT ON COLUMN workflow.contribution_batch.file_format_version IS
    'Unused from contribution file v2 onward: the file declares no version. Retained for rows written before v2.';

COMMENT ON COLUMN workflow.contribution_batch.file_validated_at IS
    'When the file was read and passed every structural check. NULL means it has not been read yet; a sweep skips the fetch and parse when it is set.';
COMMENT ON COLUMN workflow.contribution_batch.declared_employee_minor_units IS
    'Declared EMPLOYEE total in MXN cents, from the submission. A declaration, never a balance.';
COMMENT ON COLUMN workflow.contribution_batch.declared_employer_minor_units IS
    'Declared EMPLOYER total in MXN cents, from the submission. A declaration, never a balance.';
COMMENT ON COLUMN workflow.contribution_batch.declared_voluntary_minor_units IS
    'Declared VOLUNTARY total in MXN cents, from the submission. A declaration, never a balance.';

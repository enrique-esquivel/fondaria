-- A batch is sized so that its allotment is one linked chain (ADR-0017).
--
-- THE WALL THIS EXISTS TO REMOVE. AllotmentService posts one linked chain per batch:
-- two cash legs plus one unit leg per (row, source). A linked chain cannot exceed
-- 8,189 transfers -- that is TigerBeetle's one-megabyte message body, and a chain
-- must fit in a single request or it could not be atomic (LedgerLimits, measured in
-- TASK-LOG.md step 40). A 200,000-row file is 400,002 transfers and is refused
-- outright, which is where Task 21.5's fifth acceptance criterion stopped.
--
-- THE DECISION. Rather than splitting the chain -- which would give up "one linked
-- chain, fired atomically" and leave a crash mid-file with EMPLOYER_COLLECTIONS
-- partly drawn and no status saying which participants were priced -- the BATCH
-- becomes the unit of size. A file whose rows would overflow one chain is divided
-- into parts, each part is a row in this table with its own status, and one part is
-- one chain. Partial progress of a file is then exactly what the status column
-- already says, part by part.
--
-- THE FILE REMAINS THE CASH UNIT, AND THAT IS WHY PARTS ARE CHILDREN RATHER THAN
-- PEERS. An employer funds a file once, with one wire, and cannot know the subtotals
-- a splitter chose. So the reconciliation gate keeps running exactly once, on the
-- submitted row, against the total the employer declared: parent_batch_id IS NULL is
-- the whole definition of "a batch the gate, the validation sweep and the ageing
-- report look at". A part carries no cash claim of its own; it draws its own share of
-- EMPLOYER_COLLECTIONS when its chain posts, so a partly-allotted file leaves that
-- account holding precisely the unallotted remainder.
--
-- THE SUBMITTED ROW IS ALSO PART 1. It keeps its rows rather than becoming an empty
-- container, so no status has to be invented for "a batch that never allots", and a
-- file small enough to fit in one chain is completely unaffected by this migration:
-- no part is created, parent_batch_id stays NULL, and nothing about its lifecycle
-- changes.

ALTER TABLE workflow.contribution_batch
    ADD COLUMN parent_batch_id uuid REFERENCES workflow.contribution_batch (batch_id),
    ADD COLUMN part_number     integer;

-- A part number is 1-based and the submitted row is part 1, so the two columns are
-- present or absent together and a part is never numbered below 2. Nullable, and
-- checked rather than defaulted: Flyway expand/contract (AGENTS.md §4) -- the previous
-- application version writes neither column, and every row it writes is a whole file,
-- which is exactly what NULL means here.
ALTER TABLE workflow.contribution_batch
    ADD CONSTRAINT contribution_batch_part_columns_together
        CHECK ((parent_batch_id IS NULL) = (part_number IS NULL)),
    ADD CONSTRAINT contribution_batch_part_number_from_two
        CHECK (part_number IS NULL OR part_number >= 2),
    -- One level, never a tree. A part is a slice of a file, and a slice of a slice
    -- would make "which file is this" a recursive question in a place where money is
    -- being reconciled against a single declared total.
    ADD CONSTRAINT contribution_batch_part_is_not_its_own_parent
        CHECK (parent_batch_id IS NULL OR parent_batch_id <> batch_id);

-- CONTENT ADDRESSING NOW APPLIES TO FILES, NOT TO PARTS. V4's unique constraint says
-- a batch IS (organization_id, object_key, checksum_sha256), which is what makes
-- resubmitting a file converge on one row. Every part of a file shares that address by
-- construction -- it is the same file -- so the constraint has to become partial or no
-- file could ever be split. Restricted to parent_batch_id IS NULL, it still says
-- exactly what it said before about submissions, because a submission is always a
-- parent. A part is identified instead by (parent_batch_id, part_number) below, and
-- its batch_id is derived from those two, so a re-run of staging converges on the same
-- part rather than creating a second one.
ALTER TABLE workflow.contribution_batch
    DROP CONSTRAINT contribution_batch_content_address_unique;

CREATE UNIQUE INDEX contribution_batch_content_address_unique
    ON workflow.contribution_batch (organization_id, object_key, checksum_sha256)
    WHERE parent_batch_id IS NULL;

-- Also the read path for a file's parts: the status endpoint's progress summary, and
-- the cascade that moves every part to STAGED with its parent. Leading on
-- parent_batch_id, so no second index is needed to answer "the parts of this file".
CREATE UNIQUE INDEX contribution_batch_part_unique
    ON workflow.contribution_batch (parent_batch_id, part_number)
    WHERE parent_batch_id IS NOT NULL;

-- The validation sweep claims only files. A part has already passed every structural
-- check its file passed, has no cash of its own to reconcile, and re-fetching the
-- object store once per part would multiply a hundred-thousand-row parse by fifty. The
-- sweep's own query is what enforces that (parent_batch_id IS NULL, in its WHERE);
-- this index is what keeps it from walking fifty parts per file to find the next one.
--
-- V4's full index is deliberately left in place rather than replaced. The previous
-- application version's sweep does not carry the predicate and so cannot use a partial
-- index -- dropping the full one would leave it on a sequential scan, and expand/
-- contract (AGENTS.md §4) asks that the previous version keep running, not merely keep
-- returning the right answer. Contracting it is a later migration, once no version
-- that sweeps without the predicate is deployed.
CREATE INDEX contribution_batch_files_by_status_submitted
    ON workflow.contribution_batch (status, submitted_at)
    WHERE parent_batch_id IS NULL;

COMMENT ON COLUMN workflow.contribution_batch.parent_batch_id IS
    'The submitted file this batch is a part of, or NULL when this row IS a submitted file. NULL is the definition of a batch the reconciliation gate, the validation sweep and the ageing report consider: a part inherits its file''s cash decision and is never gated on its own.';
COMMENT ON COLUMN workflow.contribution_batch.part_number IS
    '1-based position of this part within its file, from 2 upward -- the submitted row is part 1 and keeps its own rows. NULL for a file that was never split.';
COMMENT ON COLUMN workflow.contribution_batch.declared_total_minor_units IS
    'MXN cents. On a submitted file, what the employer DECLARED, and the one number the reconciliation gate compares against money read from the ledger. On a part (parent_batch_id IS NOT NULL) it is instead the sum of that part''s own rows, derived from a file that has already been validated against its declaration -- a part declares nothing, and no gate ever reads it. Never one side of a balance in either case.';

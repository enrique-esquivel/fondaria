-- Task 7.4 -- the dense daily snapshot: one row per open participant position, per
-- business date, written to object storage and tied back to the ledger.
--
-- WHY workflow AND NOT reference. The same reasoning V25 records for the reconciliation
-- run: a snapshot run is a service-owned status lifecycle (RUNNING -> COMPLETED | FAILED)
-- that the sign-off gate reads (Task 7.5). It is not effective-dated reference data and
-- nothing resolves it as-of a date.
--
-- WHY THE RUN ROW AND NOT JUST THE OBJECT. The file and its manifest live in object
-- storage; this table is what makes them findable and repeatable. Three things need a
-- row in a database rather than a key in a bucket:
--
--   1. the previous business date's manifest hash, which chains each day's manifest to
--      the one before it -- resolvable in one indexed lookup instead of a bucket listing
--      whose order is not the platform's to define;
--   2. the recorded hash a re-run is compared against, which is what makes "re-run
--      produces an identical hash, or halts" enforceable rather than aspirational; and
--   3. the per-fund tie between the file's own unit totals and units_in_issue, which is
--      the single check that proves the file complete (phase-07 Task 7.4).
--
-- WHAT IS DELIBERATELY ABSENT. No Iceberg table, no object-lock retention and no Parquet.
-- Both belong to Phase 8 (Tasks 8.1 and 8.3), which is skipped -- BACKLOG.md flags this
-- exact overlap. The snapshot is written as gzipped NDJSON to plain object storage, and
-- the immutability guarantee here is that a differing re-run halts rather than
-- overwrites, not that the store refuses the write. See TASK-LOG.md.

CREATE TABLE workflow.snapshot_run (
    run_id                   uuid        NOT NULL,
    business_date            date        NOT NULL,
    status                   text        NOT NULL,
    started_at               timestamptz NOT NULL,
    completed_at             timestamptz,
    duration_millis          bigint,
    position_count           bigint      NOT NULL DEFAULT 0,
    fund_count               integer     NOT NULL DEFAULT 0,
    positions_object_key     text,
    positions_sha256         text,
    manifest_object_key      text,
    manifest_sha256          text,
    previous_manifest_sha256 text,
    failure_reason           text,

    CONSTRAINT snapshot_run_pk
        PRIMARY KEY (run_id),
    CONSTRAINT snapshot_run_status_known
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT snapshot_run_settled_when_complete
        CHECK ((status = 'RUNNING') = (completed_at IS NULL)),
    CONSTRAINT snapshot_run_counts_not_negative
        CHECK (position_count >= 0 AND fund_count >= 0),
    -- A completed snapshot always names its file and both hashes. Without that the row
    -- could not serve either of the two jobs it exists for: telling a re-run what to
    -- compare against, and giving the next day a manifest hash to chain to.
    CONSTRAINT snapshot_run_completed_names_its_objects
        CHECK (status <> 'COMPLETED'
               OR (positions_object_key IS NOT NULL AND positions_sha256 IS NOT NULL
                   AND manifest_object_key IS NOT NULL AND manifest_sha256 IS NOT NULL)),
    -- Hashes are lowercase hex SHA-256. Pinned in the schema because these are compared
    -- for equality: a run recording an uppercase digest would silently never match one
    -- recording the same bytes in lowercase, and the failure mode is "halts on a snapshot
    -- that is in fact identical", which reads exactly like real corruption.
    CONSTRAINT snapshot_run_hashes_are_hex
        CHECK ((positions_sha256 IS NULL OR positions_sha256 ~ '^[0-9a-f]{64}$')
               AND (manifest_sha256 IS NULL OR manifest_sha256 ~ '^[0-9a-f]{64}$')
               AND (previous_manifest_sha256 IS NULL
                    OR previous_manifest_sha256 ~ '^[0-9a-f]{64}$'))
);

COMMENT ON TABLE workflow.snapshot_run IS
    'One dense-snapshot pass (Task 7.4): every open participant position for a business date, valued at that date''s NAV, written to object storage as gzipped NDJSON with a manifest. The row is the record of the pass; the file is the artefact.';
COMMENT ON COLUMN workflow.snapshot_run.business_date IS
    'The close this snapshot belongs to, from BusinessCalendar -- never the wall clock. Several runs may exist for one date: a re-run is a new row, never an edit to the old one.';
COMMENT ON COLUMN workflow.snapshot_run.status IS
    'RUNNING -> COMPLETED | FAILED. Updated in place, the lifecycle workflow.contribution_batch and workflow.reconciliation_run already model. A run whose process dies stays RUNNING, so a lost process is visible as an unfinished pass rather than as no pass at all.';
COMMENT ON COLUMN workflow.snapshot_run.position_count IS
    'Open positions written to the file. bigint: a million participants across several funds passes an integer''s range sooner than it looks. This is the control total Task 7.5 counts statements against.';
COMMENT ON COLUMN workflow.snapshot_run.positions_sha256 IS
    'SHA-256 of the UNCOMPRESSED NDJSON, lowercase hex -- NOT of the stored .gz object. To reproduce it from the archive: gunzip the object, then sha256sum. The digest is over the content because a compressor''s output is a property of the compressor: java.util.zip.Deflater may emit different bytes for identical input across JDK versions, and hashing that would make an ordinary runtime upgrade look exactly like archive corruption (ADR-0020). The gzip container carries its own CRC-32 over the same plaintext, so a truncated object fails to decompress before this digest is ever computed. Written on the RUNNING row before the object is stored, so a pass that dies between the two leaves the hash it intended -- and settled unchanged when the run completes. What a re-run for the same date is compared against.';
COMMENT ON COLUMN workflow.snapshot_run.previous_manifest_sha256 IS
    'The manifest hash of the most recent COMPLETED snapshot for a strictly earlier business date, chaining each day to the one before it. Null on the first snapshot ever taken -- the genesis of the chain, and the only legitimate null.';
COMMENT ON COLUMN workflow.snapshot_run.failure_reason IS
    'Why a FAILED run failed, in operator-facing words: a hash mismatch against an existing snapshot, a fund whose units did not tie to units_in_issue, or a pass that could not finish. Never an exception message or a hostname (AGENTS.md §7).';

-- Serves two reads, both hot and both indexed by the same key. The gate and a re-run ask
-- "the latest evidence for this date"; the manifest chain asks "the latest completed run
-- strictly before this date". Ordered by completed_at, not started_at, and NULLS LAST so
-- an unfinished run sorts behind every settled one -- the same ordering, and the same
-- reason, as reconciliation_run_by_date (V25).
CREATE INDEX snapshot_run_by_date
    ON workflow.snapshot_run (business_date, completed_at DESC NULLS LAST, started_at DESC);

-- ONE SNAPSHOT OF RECORD PER DATE, ENFORCED HERE AND NOT IN THE SERVICE.
-- Two instances of eod-job may snapshot the same date at once. Both check "has this date been
-- snapshotted" after building their file and before storing it, so both can see nothing and both
-- can proceed -- and the later one to *settle* would then become the run of record while the
-- earlier one's bytes were the last written to the bucket. The audit row would name a hash the
-- stored object does not have, and because the store is write-only by design nothing could ever
-- detect it. A partial unique index makes the second settle fail loudly instead, inside the same
-- transaction that would have written its fund totals.
CREATE UNIQUE INDEX snapshot_run_one_of_record_per_date
    ON workflow.snapshot_run (business_date)
    WHERE status = 'COMPLETED';

COMMENT ON INDEX workflow.snapshot_run_one_of_record_per_date IS
    'At most one COMPLETED snapshot per business date. A re-run that reproduces the stored file records no second COMPLETED row (see SnapshotService), so this is not a constraint the ordinary path has to work around -- it is the guard against two instances both becoming the snapshot of record.';

CREATE TABLE workflow.snapshot_fund_total (
    run_id             uuid   NOT NULL REFERENCES workflow.snapshot_run (run_id),
    fund_id            uuid   NOT NULL REFERENCES reference.fund_identity (fund_id),
    unit_ledger_id     bigint NOT NULL,
    position_count     bigint NOT NULL,
    units_scaled       bigint NOT NULL,
    currency           text   NOT NULL,
    value_minor_units  bigint NOT NULL,
    units_in_issue     bigint NOT NULL,

    CONSTRAINT snapshot_fund_total_pk
        PRIMARY KEY (run_id, fund_id),
    CONSTRAINT snapshot_fund_total_counts_not_negative
        CHECK (position_count >= 0 AND units_scaled >= 0 AND units_in_issue >= 0),
    -- v1 cash is MXN-only (KEY-TYPES.md), but an amount stored without the unit it is
    -- denominated in is not reproducible, and the fund's base currency is already a
    -- registration-time property of reference.fund_identity. Carried rather than assumed.
    CONSTRAINT snapshot_fund_total_currency_known
        CHECK (currency IN ('MXN', 'USD'))
);

COMMENT ON TABLE workflow.snapshot_fund_total IS
    'The manifest''s per-fund section, and the evidence for the one check that proves a snapshot complete: SUM(units) over the file must equal units_in_issue for every fund (phase-07 Task 7.4). Written once when a run settles; never edited.';
COMMENT ON COLUMN workflow.snapshot_fund_total.units_in_issue IS
    'Read from LedgerPort.lookupAccount at snapshot time, never summed from PostgreSQL (ADR-0008). Stored beside the file''s own total so the tie is reproducible from data years later, without rerunning current code (AGENTS.md §4).';
COMMENT ON COLUMN workflow.snapshot_fund_total.value_minor_units IS
    'SUM over the file of Money.value(units, NAV) per position, MXN cents. Signed by type even though a position''s value cannot be negative: AGENTS.md §4 -- no CHECK (>= 0) on a value field, because the next thing valued here may legitimately go below zero.';

-- A snapshot's totals are the evidence its own tie rests on. Append-only for the reason
-- V25 gives for reconciliation_break: an explicit revoke plus a trigger that raises for
-- ANY role, including the one migrations run under. A fund total that could be edited
-- after the fact would let a snapshot that never tied be made to look as though it had.
REVOKE UPDATE, DELETE ON workflow.snapshot_fund_total FROM fondaria_app;
REVOKE UPDATE, DELETE ON workflow.snapshot_fund_total FROM PUBLIC;

CREATE FUNCTION workflow.snapshot_fund_total_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'workflow.snapshot_fund_total is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER snapshot_fund_total_no_update_or_delete
    BEFORE UPDATE OR DELETE ON workflow.snapshot_fund_total
    FOR EACH ROW EXECUTE FUNCTION workflow.snapshot_fund_total_immutable();

-- ---------------------------------------------------------------------------
-- The cost-basis read.
-- ---------------------------------------------------------------------------
-- A position's cost basis is the sum, over the lots that built it, of what those units
-- were worth at the price they were issued at -- workflow.allotment joined to the
-- reference.fund_nav row it names. allotment_by_holding (V25) already orders the table by
-- (organization_id, participant_internal_ref, fund_id), which is exactly the range a page
-- of the snapshot's keyset scan covers, so the lot read is an index range scan over the
-- same page and needs no index of its own.
--
-- What it does need is the join's own key. nav_version_id has a foreign key to
-- reference.fund_nav but no index on this side, so the lot read would hash the whole NAV
-- table per page. allotment_by_nav_version (V19) is on workflow.allotment, answering the
-- opposite question ("which allotments used this NAV"); this is reference.fund_nav's own
-- primary key doing the work, and a lookup by nav_version_id is already served by it.
-- Nothing further is added here deliberately -- see JdbcCostBasisSource.

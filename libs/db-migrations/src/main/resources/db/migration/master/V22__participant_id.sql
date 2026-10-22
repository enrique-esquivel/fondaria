-- Task 6.2 — master.participant.participant_id, the identifier external contracts carry.
--
-- Phase 6's read paths are /v1/participants/{participantId}/..., and until now
-- there was nothing to put there. The table's primary key is
-- (organization_id, participant_internal_ref) and V6's own header argues for
-- keeping it that way: the internal ref is stable, never reused, and is what
-- makes auto-enrolment idempotent on INSERT. None of that changes here. This
-- column is additive and exists for one reason: an internal ref identifies a
-- person exactly as an RFC does, and docs/ENGINEERING-RULES.md §7 keeps such a value out of
-- every URL, access log, proxy log and trace. Something else has to travel.
--
-- RANDOM, NOT DERIVED. OrganizationId is UUIDv5(namespace, taxId) because
-- registering the same employer twice must yield the same id with no registry
-- to consult. A participant needs no such property: enrolment is already
-- idempotent through the primary key above, so the id carries none of that
-- weight. Deriving it would actively cost something instead — UUIDv5(namespace,
-- organization_id + ':' + internal_ref) is computable by anyone holding the
-- namespace, which turns the id into a confirmation oracle for whether a given
-- payroll reference exists in a given organization. Random closes that. The
-- precedent is FundId, random at registration for the neighbouring reason (no
-- external natural key to derive from).
--
-- The DEFAULT is what backfills existing rows and what keeps this migration
-- expand/contract safe (docs/ENGINEERING-RULES.md §4): a volatile default makes PostgreSQL
-- rewrite the table and evaluate gen_random_uuid() once per existing row, so
-- every row emerges with its own id, and the previous application version --
-- which never names this column in its INSERT -- keeps running unchanged.
-- contribution-api therefore needs no code change to populate it.
--
-- gen_random_uuid() is built into PostgreSQL 13+ (pinned: 17). No pgcrypto, no
-- extension to install.
--
-- THE REWRITE IS NOT FREE, and expand/contract does not mean zero-downtime here.
-- PostgreSQL's fast-default path (attmissingval, since 11) applies only to a
-- NON-volatile default; gen_random_uuid() is VOLATILE, which is precisely why
-- each existing row gets its own value -- and the cost is a full table rewrite
-- holding ACCESS EXCLUSIVE on master.participant, followed by a non-CONCURRENT
-- unique index build holding SHARE. For that window the previous application
-- version blocks on every read and write of this table rather than running
-- alongside. At v1 scale that is seconds; on a large table it would not be, and
-- a future variant should add the column nullable, backfill in batches, then
-- CREATE UNIQUE INDEX CONCURRENTLY and SET NOT NULL.
--
-- If the per-row evaluation ever did not hold, the failure is loud rather than
-- silent: the unique index below would fail to build and the migration would
-- abort. Verified against postgres:17-alpine -- 5,000 pre-existing rows in,
-- 5,000 distinct ids out, index builds.

ALTER TABLE master.participant
    ADD COLUMN participant_id uuid NOT NULL DEFAULT gen_random_uuid();

-- Unique, because this is what a URL names: two participants resolving to one
-- id would serve one person's balance to another. Also the index the balance
-- read's participant_id -> (organization_id, participant_internal_ref)
-- resolution uses; that lookup is on the hot path and must never be a scan.
CREATE UNIQUE INDEX participant_id_unique
    ON master.participant (participant_id);

COMMENT ON COLUMN master.participant.participant_id IS
    'Opaque, randomly assigned identifier carried by external contracts (contracts/openapi/query-api.yaml). Assigned once at enrolment by the column default and never reassigned -- a URL naming it must keep resolving to the same person forever. Deliberately NOT derived from (organization_id, participant_internal_ref): a derivable id would let anyone holding the namespace test whether a payroll reference exists. Never itself logged; it is an identifier for a person (docs/ENGINEERING-RULES.md §7).';

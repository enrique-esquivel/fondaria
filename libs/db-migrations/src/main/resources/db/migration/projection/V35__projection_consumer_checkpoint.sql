-- Row 26e -- one checkpoint row per AMQP consumer (ADR-0032 §1).
--
-- WHY A NEW TABLE. projection.projection_checkpoint is one row that every sink
-- transaction upserts with GREATEST, so every consumer thread serialized on that
-- row's lock (the 2026-09-27 rehearsal: ~460k messages queued, 18 min of CDC lag,
-- the pod at ~0.5 CPU). Here each consumer -- `<pod>/<AMQP consumer tag>` -- owns
-- its own row, so no two threads ever write the same row.
--
-- EXPAND ONLY. projection.projection_checkpoint is deliberately left exactly as it
-- is: the previous application version upserts it with ON CONFLICT (projection_name),
-- which needs that primary key. Re-keying it in place would fail every old-version
-- transaction during a rolling deploy. Old pods keep advancing the old row, new pods
-- write only this table. Dropping the old table is a later contract migration, once
-- no version that writes it is deployable anywhere.
--
-- WHAT A ROW MEANS. last_ledger_timestamp is the highest ledger timestamp THIS
-- consumer has committed, advanced in the same transaction as the rows it covers, so
-- a row never runs ahead of its own consumer's committed data. The worker's "head"
-- (MIN over consumers active within a window, else MAX) is a monitoring estimate,
-- not a gap-free watermark, and never a correctness input: replay is truncate plus
-- full rebuild (docs/RUNBOOKS.md#cdc-replay).

CREATE TABLE projection.projection_consumer_checkpoint (
    projection_name       text          NOT NULL,
    consumer_id           text          NOT NULL,
    queue_name            text          NOT NULL,
    last_ledger_timestamp numeric(20,0) NOT NULL,
    updated_at            timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT projection_consumer_checkpoint_pk
        PRIMARY KEY (projection_name, consumer_id),
    CONSTRAINT projection_consumer_checkpoint_consumer_not_blank
        CHECK (length(consumer_id) > 0),
    CONSTRAINT projection_consumer_checkpoint_not_negative
        CHECK (last_ledger_timestamp >= 0)
);

COMMENT ON TABLE projection.projection_consumer_checkpoint IS
    'How far each projection consumer has committed, by ledger_timestamp: one row per AMQP consumer, written only by that consumer (ADR-0032). A monitoring head, not a replay cursor. Mutable in place and pruned by age -- the same ADR-0011 exception as projection_checkpoint; it is not a movement or audit table.';
COMMENT ON COLUMN projection.projection_consumer_checkpoint.consumer_id IS
    '<pod name>/<AMQP consumer tag>. A reconnect gets a new tag and so a new row; the old row ages out.';
COMMENT ON COLUMN projection.projection_consumer_checkpoint.queue_name IS
    'The queue this consumer drains. Informational (runbook); not part of the key.';
COMMENT ON COLUMN projection.projection_consumer_checkpoint.last_ledger_timestamp IS
    'Highest ledger_timestamp this consumer has committed (GREATEST: its deliveries are not timestamp-ordered). Never ahead of that consumer''s own committed data; says nothing about other consumers.';
COMMENT ON COLUMN projection.projection_consumer_checkpoint.updated_at IS
    'Time of the last advance. Rows idle past the active window drop out of the head; rows idle past the retention are deleted.';

-- V1's default privileges give fondaria_cdc SELECT, INSERT and fondaria_app SELECT on a
-- new projection table. The consumer also advances its row in place and prunes
-- stale rows (any replica may delete any consumer's stale row).
GRANT UPDATE, DELETE ON projection.projection_consumer_checkpoint TO fondaria_cdc;

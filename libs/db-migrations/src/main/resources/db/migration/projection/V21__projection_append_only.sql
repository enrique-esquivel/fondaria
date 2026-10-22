-- Task 5.2 -- append-only enforcement on the projection movement tables.
--
-- DEVIATION FROM THE PHASE DOC, DELIBERATE. Task 5.2 says "REVOKE UPDATE, DELETE,
-- TRUNCATE on transfer, participant_units and fund_nav ... plus BEFORE UPDATE OR
-- DELETE triggers". Taken literally that breaks Task 5.5: `participant_units` is
-- maintained by a guarded in-place upsert -- `ON CONFLICT DO UPDATE ... WHERE
-- incoming ledger_timestamp > stored` -- so that redelivery and out-of-order
-- arrival are no-ops and same-value-date entries converge to the latest holding.
-- A raise-on-UPDATE trigger would turn every benign redelivery into a poison
-- message. So:
--
--   * projection.transfer          -- fully immutable. REVOKE UPDATE/DELETE/TRUNCATE
--                                     from every runtime role + a BEFORE UPDATE OR
--                                     DELETE raise trigger. Task 5.4 inserts with
--                                     ON CONFLICT DO NOTHING, so it never needs to
--                                     update a row.
--   * projection.participant_units -- REVOKE UPDATE/DELETE/TRUNCATE from fondaria_app
--                                     (the Query API and everything else may only
--                                     read) and PUBLIC; fondaria_cdc keeps UPDATE for
--                                     Task 5.5's guarded upsert. A BEFORE DELETE
--                                     raise trigger for every role -- a holding row
--                                     is never destroyed. Monotonicity of the
--                                     UPDATE is the consumer's WHERE clause, and
--                                     the per-(participant,fund) row_hash chain
--                                     (Task 5.5) makes any out-of-band edit
--                                     detectable (Task 5.7 recomputes it).
--   * reference.fund_nav           -- already carries REVOKE + an immutable trigger
--                                     from V11__fund_nav.sql (it lives in
--                                     `reference`, per BACKLOG.md "Settled"). Not
--                                     touched here.
--
-- projection_checkpoint is not locked at all: the replay cursor advances in place
-- (Task 5.4).
--
-- TRUNCATE stays available to the table owner / a superuser -- that is the
-- projection-rebuild path (Task 5.8, `make rebuild-projection`). Only the runtime
-- roles lose it. DDL (ALTER TABLE) is unaffected: the triggers are FOR EACH ROW on
-- DML and the migration role keeps its ownership rights.

REVOKE UPDATE, DELETE, TRUNCATE ON projection.transfer          FROM fondaria_app, fondaria_cdc, PUBLIC;
REVOKE         DELETE, TRUNCATE ON projection.participant_units  FROM fondaria_app, fondaria_cdc, PUBLIC;
REVOKE UPDATE                   ON projection.participant_units  FROM fondaria_app, PUBLIC;

-- The CDC consumer keeps UPDATE on participant_units, and only there: Task 5.5's
-- `ON CONFLICT DO UPDATE ... WHERE incoming ledger_timestamp > stored` upsert. The
-- schema-default grant (V1) is SELECT, INSERT only, so this is additive.
GRANT UPDATE ON projection.participant_units TO fondaria_cdc;

CREATE FUNCTION projection.reject_mutation() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '%.% is append-only: % is not permitted (Task 5.2)',
        TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP;
END;
$$;

COMMENT ON FUNCTION projection.reject_mutation() IS
    'Raised by the append-only triggers on the projection movement tables. A correction is a new row, never an edit (AGENTS.md §4).';

-- A row trigger on a partitioned parent propagates to every existing and future
-- partition (Postgres 13+), so tenant/month partitions created later by
-- projection.ensure_tenant_partitions inherit the guard with no extra work.
CREATE TRIGGER transfer_append_only
    BEFORE UPDATE OR DELETE ON projection.transfer
    FOR EACH ROW EXECUTE FUNCTION projection.reject_mutation();

CREATE TRIGGER participant_units_no_delete
    BEFORE DELETE ON projection.participant_units
    FOR EACH ROW EXECUTE FUNCTION projection.reject_mutation();

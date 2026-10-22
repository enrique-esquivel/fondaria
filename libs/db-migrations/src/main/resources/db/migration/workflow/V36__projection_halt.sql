-- Row 26f -- the cluster-wide projection halt flag (ADR-0032 §2).
--
-- WHY A TABLE, AND WHY IN workflow. "Send it to the DLQ and halt" (docs/ENGINEERING-RULES.md §4) used
-- to be a per-process latch: with two replicas, one halted while the other kept
-- consuming and acking every other queue, readiness UP. A halt is operational state
-- with a lifecycle -- raised by a worker, cleared by an operator -- so it belongs in
-- `workflow` (ADR-0011), not in CDC-fed `projection`, and it must survive a
-- projection rebuild (which truncates `projection`) rather than be cleared by it.
--
-- HOW IT IS USED. A worker that meets a poison message INSERTs a row before it
-- publishes to the DLQ. Every replica polls for an uncleared row, stops its listener
-- containers and reports readiness DOWN; a pod that starts during a halt never
-- consumes. An operator clears it with an UPDATE recording who and why, then restarts
-- the Deployment (docs/RUNBOOKS.md#cdc-replay §2).
--
-- NO PAYLOAD. The row names the source queue and a reason code only: a decode
-- failure's text can quote message content. The bytes are in the DLQ; the full reason
-- is in the halting pod's log (docs/ENGINEERING-RULES.md §7).

CREATE TABLE workflow.projection_halt (
    halt_id         bigint      GENERATED ALWAYS AS IDENTITY,
    projection_name text        NOT NULL,
    halted_at       timestamptz NOT NULL DEFAULT now(),
    halted_by       text        NOT NULL,
    reason_code     text        NOT NULL,
    source_queue    text        NOT NULL,
    cleared_at      timestamptz,
    cleared_by      text,
    clear_note      text,

    CONSTRAINT projection_halt_pk
        PRIMARY KEY (halt_id),
    CONSTRAINT projection_halt_reason_known
        CHECK (reason_code IN ('UNKNOWN_SCHEMA_VERSION', 'MALFORMED_EVENT')),
    -- Clearing is one act: when, who and why arrive together or not at all.
    CONSTRAINT projection_halt_clear_complete
        CHECK (
            (cleared_at IS NULL AND cleared_by IS NULL AND clear_note IS NULL)
            OR (cleared_at IS NOT NULL
                AND cleared_by IS NOT NULL AND length(btrim(cleared_by)) > 0
                AND clear_note IS NOT NULL AND length(btrim(clear_note)) > 0)
        )
);

-- At most one active halt per projection: a second replica that meets the same poison
-- runs INSERT ... ON CONFLICT DO NOTHING and the first row stands.
CREATE UNIQUE INDEX projection_halt_one_active
    ON workflow.projection_halt (projection_name)
    WHERE cleared_at IS NULL;

COMMENT ON TABLE workflow.projection_halt IS
    'One row per projection halt (ADR-0032 §2). An uncleared row stops every projection-worker replica. Raised by a worker, cleared in place by an operator; cleared rows stay as the incident record.';
COMMENT ON COLUMN workflow.projection_halt.halted_by IS
    'The pod (PROJECTION_INSTANCE_ID) that raised the halt.';
COMMENT ON COLUMN workflow.projection_halt.reason_code IS
    'The dead-letter reason code. Never message content or an exception message.';
COMMENT ON COLUMN workflow.projection_halt.source_queue IS
    'The queue the poison delivery came from.';

-- fondaria_cdc reaches workflow for this one table only: it raises a halt and reads it,
-- and never clears one. V1's default privileges give fondaria_app SELECT, INSERT, UPDATE on
-- every new workflow table; no request-path service raises or clears a projection halt, so
-- take the writes back. Clearing is an operator UPDATE run as the owner role.
GRANT USAGE ON SCHEMA workflow TO fondaria_cdc;
GRANT SELECT, INSERT ON workflow.projection_halt TO fondaria_cdc;
REVOKE INSERT, UPDATE ON workflow.projection_halt FROM fondaria_app;

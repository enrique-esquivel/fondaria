-- Task 9.4 — Staff actions and four-eyes separation.
--
-- Actions affecting participant money (corrections, NAV supersession, manual
-- adjustments, kill switches) require a second, different approver, enforced at the
-- database level (requested_by <> approved_by as a constraint, not just a code check).
--
-- Every such action records: who, when, why, what changed, and the control totals
-- stated beforehand.
--
-- Break-glass access is possible, time-boxed, alerting, and reviewed after the fact.
-- Audit trail is append-only and cryptographically hash-chained.

CREATE TABLE workflow.staff_action (
    action_id               uuid        NOT NULL,
    action_type             text        NOT NULL,
    description             text        NOT NULL,
    control_totals          jsonb       NOT NULL,
    requested_by            text        NOT NULL,
    requested_at            timestamptz NOT NULL,
    status                  text        NOT NULL,
    approved_by             text,
    approved_at             timestamptz,
    is_break_glass          boolean     NOT NULL DEFAULT false,
    break_glass_reason      text,
    break_glass_expires_at  timestamptz,

    CONSTRAINT staff_action_pk
        PRIMARY KEY (action_id),
    CONSTRAINT staff_action_type_known
        CHECK (action_type IN ('CORRECTION', 'NAV_SUPERSESSION', 'MANUAL_ADJUSTMENT', 'KILL_SWITCH')),
    CONSTRAINT staff_action_status_known
        CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'EXECUTED')),
    CONSTRAINT staff_action_four_eyes
        CHECK (is_break_glass OR approved_by IS NULL OR lower(requested_by) <> lower(approved_by)),
    CONSTRAINT staff_action_break_glass_requirements
        CHECK (NOT is_break_glass OR (break_glass_reason IS NOT NULL AND break_glass_expires_at IS NOT NULL)),
    CONSTRAINT staff_action_approval_consistency
        CHECK (
            (status = 'PENDING_APPROVAL' AND approved_by IS NULL AND approved_at IS NULL)
            OR (status IN ('APPROVED', 'EXECUTED') AND approved_by IS NOT NULL AND approved_at IS NOT NULL)
            OR (status = 'REJECTED')
        )
);

COMMENT ON TABLE workflow.staff_action IS
    'Privileged staff actions affecting participant money (Task 9.4). Enforces four-eyes dual authorization at database level via CHECK (requested_by <> approved_by).';
COMMENT ON COLUMN workflow.staff_action.control_totals IS
    'Control totals stated beforehand (e.g. affected participants, net cash, unit impacts) in JSONB format.';
COMMENT ON COLUMN workflow.staff_action.is_break_glass IS
    'Whether this action was initiated under emergency break-glass procedure bypassing dual approval, requiring explicit reason and expiration.';

-- ---------------------------------------------------------------------------
-- Append-only, hash-chained audit trail for privileged staff actions.
-- ---------------------------------------------------------------------------

CREATE TABLE workflow.staff_action_audit (
    audit_id        bigserial   NOT NULL,
    sequence        bigint      NOT NULL,
    action_id       uuid        NOT NULL REFERENCES workflow.staff_action (action_id),
    action_type     text        NOT NULL,
    event_type      text        NOT NULL,
    actor           text        NOT NULL,
    recorded_at     timestamptz NOT NULL,
    payload         text        NOT NULL,
    prev_hash       text        NOT NULL,
    entry_hash      text        NOT NULL,

    CONSTRAINT staff_action_audit_pk
        PRIMARY KEY (audit_id),
    CONSTRAINT staff_action_audit_sequence_unique
        UNIQUE (sequence),
    CONSTRAINT staff_action_audit_event_type_known
        CHECK (event_type IN ('REQUESTED', 'APPROVED', 'REJECTED', 'EXECUTED', 'BREAK_GLASS_TRIGGERED')),
    CONSTRAINT staff_action_audit_hashes_format
        CHECK (length(prev_hash) = 64 AND length(entry_hash) = 64)
);

COMMENT ON TABLE workflow.staff_action_audit IS
    'Append-only, cryptographically hash-chained audit log for staff actions (Task 9.4). Guaranteed immutable via triggers and privilege revokes.';

REVOKE UPDATE, DELETE, TRUNCATE ON workflow.staff_action_audit FROM fondaria_app;
REVOKE UPDATE, DELETE, TRUNCATE ON workflow.staff_action_audit FROM PUBLIC;

CREATE FUNCTION workflow.staff_action_audit_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'workflow.staff_action_audit is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER staff_action_audit_no_update_or_delete
    BEFORE UPDATE OR DELETE ON workflow.staff_action_audit
    FOR EACH ROW EXECUTE FUNCTION workflow.staff_action_audit_immutable();

CREATE TRIGGER staff_action_audit_no_truncate
    BEFORE TRUNCATE ON workflow.staff_action_audit
    FOR EACH STATEMENT EXECUTE FUNCTION workflow.staff_action_audit_immutable();

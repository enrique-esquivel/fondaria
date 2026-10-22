-- ADR-0011 — where non-ledger data lives.
--
-- PostgreSQL has four roles, and they are separated by schema and enforced by
-- database grant rather than by convention. All four are created here, in one
-- baseline, because the separation is the decision: a later task that needed
-- only its own schema would otherwise be free to invent a different layout.
--
-- What is NOT decided here: which login user a deployment connects as. The two
-- roles below are passwordless NOLOGIN group roles carrying the grants; binding
-- a login user into one of them is deployment configuration (docs/ENGINEERING-RULES.md §4 —
-- no credentials in the repository).

CREATE SCHEMA IF NOT EXISTS projection;
CREATE SCHEMA IF NOT EXISTS master;
CREATE SCHEMA IF NOT EXISTS reference;
CREATE SCHEMA IF NOT EXISTS workflow;

COMMENT ON SCHEMA projection IS
    'CDC-fed only. Never written by a service, never the authority for a balance, never rule enforcement (ADR-0011).';
COMMENT ON SCHEMA master IS
    'Master data — organization, participant, plan, product, fund registry. Current state, mutable, written directly by services.';
COMMENT ON SCHEMA reference IS
    'Effective-dated versioned data and rule-evaluation evidence. Append-only: UPDATE and DELETE are revoked, so a correction is a new row by construction.';
COMMENT ON SCHEMA workflow IS
    'Service-owned operational state and its status lifecycle. Mutable.';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fondaria_app') THEN
        CREATE ROLE fondaria_app NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fondaria_cdc') THEN
        CREATE ROLE fondaria_cdc NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA projection, master, reference, workflow TO fondaria_app;
GRANT USAGE ON SCHEMA projection TO fondaria_cdc;

-- projection: the application reads, the CDC consumer appends, nobody mutates.
ALTER DEFAULT PRIVILEGES IN SCHEMA projection GRANT SELECT ON TABLES TO fondaria_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA projection GRANT SELECT, INSERT ON TABLES TO fondaria_cdc;

-- master: current state, so UPDATE is granted. History is kept in its own
-- append-only table, which revokes UPDATE/DELETE at table level (V2).
ALTER DEFAULT PRIVILEGES IN SCHEMA master GRANT SELECT, INSERT, UPDATE ON TABLES TO fondaria_app;

-- reference: append-only. UPDATE/DELETE are simply never granted.
ALTER DEFAULT PRIVILEGES IN SCHEMA reference GRANT SELECT, INSERT ON TABLES TO fondaria_app;

-- workflow: a status lifecycle moves in place.
ALTER DEFAULT PRIVILEGES IN SCHEMA workflow GRANT SELECT, INSERT, UPDATE ON TABLES TO fondaria_app;

-- Sequences follow their tables: a role that may INSERT must be able to draw
-- from a serial column's sequence.
ALTER DEFAULT PRIVILEGES IN SCHEMA master, reference, workflow GRANT USAGE ON SEQUENCES TO fondaria_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA projection GRANT USAGE ON SEQUENCES TO fondaria_cdc;

-- PUBLIC gets nothing anywhere. A grant is a decision, never a default.
REVOKE ALL ON SCHEMA projection, master, reference, workflow FROM PUBLIC;

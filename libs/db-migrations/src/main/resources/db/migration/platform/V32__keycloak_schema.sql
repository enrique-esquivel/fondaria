-- Task 9.1 — Keycloak on its own PostgreSQL schema, separate from the platform's.
--
-- Keycloak manages its own tables, indexes, and internal schema migrations directly.
-- Creating the schema here ensures it is provisioned before Keycloak starts up against
-- the shared database instance, while keeping identity tables isolated from the
-- application's four schemas (master, reference, workflow, projection per ADR-0011).

CREATE SCHEMA IF NOT EXISTS keycloak;

COMMENT ON SCHEMA keycloak IS
    'Keycloak identity provider data. Managed by Keycloak directly, separate from the platform application schemas.';

-- Public role gets nothing.
REVOKE ALL ON SCHEMA keycloak FROM PUBLIC;

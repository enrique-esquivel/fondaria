-- Task 2b.1 — the organization registry.
--
-- ADR-0011 records why this table exists: organization_id is derived from the
-- tax identifier by SHA-1, which is one-way, so without a row here nothing in
-- the system could say whose money a ledger account holds, list organizations,
-- or reject a well-formed RFC belonging to nobody.
--
-- The id stays derived (organization_id = UUIDv5(namespace, taxId)) rather than
-- assigned, so registering twice converges on one row without consulting this
-- table first. The unique constraint on the tax identifier is a second line of
-- defence, not the mechanism.

CREATE TABLE master.organization (
    organization_id     uuid        PRIMARY KEY,
    tax_id_scheme       text        NOT NULL,
    tax_id              text        NOT NULL,
    legal_name          text        NOT NULL,
    contract_reference  text,
    fee_bracket         text,
    status              text        NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,

    CONSTRAINT organization_tax_id_unique
        UNIQUE (tax_id_scheme, tax_id),
    CONSTRAINT organization_legal_name_present
        CHECK (length(btrim(legal_name)) > 0),
    CONSTRAINT organization_status_known
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

COMMENT ON TABLE master.organization IS
    'Client organizations. An organization is a tenant (ADR-0005), so organization_id is the tenant_id every other table is scoped by — there is no separate tenant column here, because this table is the tenant list.';
COMMENT ON COLUMN master.organization.organization_id IS
    'UUIDv5(organizationNamespace, taxId.derivationKey()). Derived, never assigned, and never changed by an update — it is what the ledger accounts holding this employer''s money are derived from.';
COMMENT ON COLUMN master.organization.tax_id IS
    'Canonical RFC. Never appears in a URL, log line, message key or trace (AGENTS.md §7); only organization_id travels.';
COMMENT ON COLUMN master.organization.fee_bracket IS
    'Commercial bracket code agreed in the contract. Opaque here on purpose: the fee schedule it selects is effective-dated reference data owned by Task 2b.2, and duplicating its shape in master data would create a second, un-versioned copy.';

-- Every registration and every update appends one row. `master` grants UPDATE
-- for current state; this table opts out at table level, so "an update never
-- rewrites history" is enforced by the database rather than by discipline.
CREATE TABLE master.organization_revision (
    organization_id     uuid        NOT NULL REFERENCES master.organization (organization_id),
    revision            integer     NOT NULL,
    legal_name          text        NOT NULL,
    contract_reference  text,
    fee_bracket         text,
    status              text        NOT NULL,
    recorded_at         timestamptz NOT NULL,

    CONSTRAINT organization_revision_pk
        PRIMARY KEY (organization_id, revision),
    CONSTRAINT organization_revision_positive
        CHECK (revision >= 1)
);

COMMENT ON TABLE master.organization_revision IS
    'Append-only history of master.organization. Revision 1 is the registration; each accepted update appends the next. Never UPDATEd, never DELETEd (AGENTS.md §4).';

REVOKE UPDATE, DELETE ON master.organization_revision FROM fondaria_app;
REVOKE UPDATE, DELETE ON master.organization_revision FROM PUBLIC;

-- Task 7.3 -- custodian reconciliation: which account at the external provider a fund's
-- units and cash are reconciled against.
--
-- WHY reference AND NOT workflow. ADR-0011: reference holds effective-dated data, resolved
-- as-of a date, never UPDATEd -- the same shape reference.plan_version already uses. A
-- provider account is exactly that: it can change (an employer moves from omnibus to
-- segregated custody), and every reconciliation run must record which version of it applied.
-- It is not a status lifecycle -- there is no RUNNING/PASSED for a provider account, so it
-- does not belong in workflow the way workflow.custodian_statement or workflow.custodian_break
-- (V27) do.
--
-- ADR-0019 -- omnibus vs. segregated is an attribute of the account, not a platform mode.
-- ALL_PARTICIPANTS is exactly one row per fund and is what v1 ships: reconciliation resolves
-- straight to units_in_issue, the same figure Task 7.2 already reads. ORGANIZATION is one row
-- per fund per employer, scoped by organization_id rather than plan_id because plan-to-fund
-- linkage (O-24/O-28, BACKLOG.md row 5a) is itself still an open decision -- segregated custody
-- must not depend on it. organization_id is the platform's already-resolved tenancy boundary
-- (ADR-0005), and every holding is already reachable from it via workflow.allotment.
--
-- Cash is deliberately NOT scoped here. There is exactly one FUND_CASH account per fund
-- (chart-of-accounts.md Sec 2); segregating cash would need a new ledger account shape, a
-- decision ADR-0019 explicitly does not make. Every provider account for a fund, of any scope,
-- reconciles cash against that one FUND_CASH balance.

CREATE TABLE reference.provider_account (
    provider_account_id  uuid        NOT NULL,
    fund_id              uuid        NOT NULL REFERENCES reference.fund_identity (fund_id),
    scope_type           text        NOT NULL,
    organization_id      uuid        REFERENCES master.organization (organization_id),
    provider_ref         text        NOT NULL,
    effective_from       date        NOT NULL,
    version              integer     NOT NULL,
    published_at         timestamptz NOT NULL,

    CONSTRAINT provider_account_pk
        PRIMARY KEY (provider_account_id),
    CONSTRAINT provider_account_scope_type_known
        CHECK (scope_type IN ('ALL_PARTICIPANTS', 'ORGANIZATION')),
    CONSTRAINT provider_account_org_matches_scope
        CHECK ((scope_type = 'ALL_PARTICIPANTS') = (organization_id IS NULL)),
    CONSTRAINT provider_account_ref_not_blank
        CHECK (btrim(provider_ref) <> '')
);

COMMENT ON TABLE reference.provider_account IS
    'Which account at the external fund or bank a fund''s units (and, for cash, always the fund itself) are reconciled against (Task 7.3, ADR-0019). Effective-dated: resolution is always as-of a date, the highest (effective_from, version) not after the date asked for, exactly as reference.plan_version resolves.';
COMMENT ON COLUMN reference.provider_account.scope_type IS
    'ALL_PARTICIPANTS -- omnibus, one row per fund, resolves to units_in_issue directly. ORGANIZATION -- segregated, one row per fund per employer, resolves by summing that organization''s holdings from workflow.allotment. Never PLAN: plan-to-fund linkage is still open (O-24/O-28).';
COMMENT ON COLUMN reference.provider_account.provider_ref IS
    'The external identifier for this account at the provider (account number, mandate reference). Descriptive data about a counterparty relationship, not money or a ledger link -- lives here, never in TigerBeetle user data (docs/ENGINEERING-RULES.md Sec 4).';
COMMENT ON COLUMN reference.provider_account.version IS
    'Disambiguates two versions published with the same effective_from. The higher value wins resolution; the lower one remains readable.';

-- Resolution: for a scope key, the highest (effective_from, version) not after the date asked
-- for. Two partial indexes because organization_id is NULL for ALL_PARTICIPANTS and Postgres
-- treats NULLs as distinct in a plain UNIQUE constraint -- a plain unique index over all four
-- columns would silently allow two ALL_PARTICIPANTS rows for the same fund and date.
CREATE UNIQUE INDEX provider_account_all_participants_version_unique
    ON reference.provider_account (fund_id, effective_from, version)
    WHERE scope_type = 'ALL_PARTICIPANTS';

CREATE UNIQUE INDEX provider_account_organization_version_unique
    ON reference.provider_account (fund_id, organization_id, effective_from, version)
    WHERE scope_type = 'ORGANIZATION';

-- Serves "every provider account active as of a business date", across every fund and scope,
-- in one pass -- what the reconciliation service reads at the start of every run.
CREATE INDEX provider_account_by_fund_and_date
    ON reference.provider_account (fund_id, scope_type, organization_id, effective_from DESC, version DESC);

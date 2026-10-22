-- Task 5.1 -- the CDC-fed projection schema (phase-05-projection.md).
--
-- WHAT LIVES HERE. Three tables in `projection`: the append-only movement log
-- (`transfer`), the sparse per-participant unit holding (`participant_units`) and
-- the consumer's replay cursor (`projection_checkpoint`). ADR-0011: `projection`
-- is CDC-fed, never written by a service, never the authority for a balance, never
-- rule enforcement. A balance is still read only from LedgerPort (ADR-0008).
--
-- WHAT DELIBERATELY DOES NOT. The phase-doc sketch also drew `CREATE TABLE fund_nav`
-- here. It is not created: BACKLOG.md "Settled -- do not re-litigate" records that a
-- struck NAV is written directly by valuation-api and is rule-evaluation evidence,
-- not a replicated fact, so it lives in `reference` (V11__fund_nav.sql). Value on any
-- date is `units(as-of) x NAV(date)` -- units sparse here, NAV dense in `reference`,
-- value computed on demand and never stored (docs/DOMAIN.md).
--
-- NUMERIC WIDTHS.
--   * transfer_id, debit_account_id, credit_account_id, participant_id,
--     last_transfer_id are TigerBeetle u128 identifiers. BIGINT tops out at
--     2^63-1 (~9.2e18, 19 digits); a u128 ranges to 2^128-1 (~3.4e38, 39 digits),
--     so NUMERIC(39,0) -- exact, zero scale. The CDC decoder (libs/ledger-client)
--     carries these as canonical decimal strings for exactly this reason; they are
--     stored, never arithmetic'd.
--   * ledger_timestamp / last_ledger_timestamp are TigerBeetle's u64 nanosecond
--     clock -- up to 20 digits -- so NUMERIC(20,0).
--   * amount_scaled / units_scaled are the platform's own signed-64-bit quantities
--     (Money cents, Units at 1e-8): BIGINT. The projection only ever receives a
--     ledger leg's own amount, which is a positive magnitude; direction is which
--     account was debited vs credited, never the sign of the number.
--
-- PARTITIONING (ADR-0005: tenant-first, `(tenant_id, participant_id)`).
--   * `transfer` and `participant_units` both PARTITION BY LIST (tenant_id) with a
--     DEFAULT catch-all. LIST, not the sketch's RANGE (tenant_id): tenant ids are
--     discrete point values and RANGE upper bounds on text buy nothing but a wart.
--   * each onboarded tenant's `transfer` partition is itself PARTITION BY RANGE
--     (value_date), one leaf per calendar month, so Phase 15's era/archival model
--     can detach a whole month cheaply. `participant_units` is sparse (~500k
--     rows/year at 250k participants) and gets no date sub-partitioning.
--   * two-level partitioning requires every partition-key column in the primary
--     key, so `transfer`'s PK is (tenant_id, transfer_id, value_date), not the
--     sketch's (tenant_id, transfer_id). value_date is deterministic for a given
--     transfer (its booking date, or the explicit earlier value date the CDC event
--     carries -- O-4), so a redelivery lands on the same row and Task 5.4's
--     idempotent insert targets (tenant_id, transfer_id, value_date).
--   * partitions for a tenant are created at onboarding by
--     `projection.ensure_tenant_partitions`, monthly leaves by
--     `projection.ensure_transfer_month` / `ensure_upcoming_transfer_months`.
--     Runtime creation goes through SECURITY DEFINER functions so the runtime role
--     never holds DDL rights (Task 5.2).

-- ---------------------------------------------------------------------------
-- transfer -- the append-only projection of every ledger movement.
-- ---------------------------------------------------------------------------
CREATE TABLE projection.transfer (
    transfer_id           numeric(39,0) NOT NULL,
    tenant_id             text          NOT NULL,
    ledger_timestamp      numeric(20,0) NOT NULL,
    ledger_id             integer       NOT NULL,
    debit_account_id      numeric(39,0) NOT NULL,
    credit_account_id     numeric(39,0) NOT NULL,
    amount_scaled         bigint        NOT NULL,
    code                  integer       NOT NULL,
    booking_date          date          NOT NULL,
    value_date            date          NOT NULL,
    nav_version_id        bigint,
    reference             text,
    created_at            timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT transfer_pk
        PRIMARY KEY (tenant_id, transfer_id, value_date),
    CONSTRAINT transfer_amount_positive
        CHECK (amount_scaled > 0),
    -- Matches the CDC decoder's own routing: cash ledger 484, or a per-fund unit
    -- ledger from 1001 (LedgerCodes). A ledger id outside this set is a malformed
    -- event the consumer must DLQ, not a row it may store.
    CONSTRAINT transfer_ledger_id_known
        CHECK (ledger_id = 484 OR ledger_id >= 1001)
) PARTITION BY LIST (tenant_id);

COMMENT ON TABLE projection.transfer IS
    'Append-only projection of every ledger movement, one row per TigerBeetle transfer (phase-05). Never updated or deleted (Task 5.2). Not a balance store -- a balance is read from LedgerPort, never summed here (ADR-0008).';
COMMENT ON COLUMN projection.transfer.transfer_id IS
    'TigerBeetle u128 transfer id, derived deterministically from an idempotency key. NUMERIC(39,0): a u128 does not fit BIGINT.';
COMMENT ON COLUMN projection.transfer.tenant_id IS
    'Client organization id -- the same identifier used in transfer_id derivation and carried as the Redpanda message-key prefix (ADR-0005). Partition key, tenant first.';
COMMENT ON COLUMN projection.transfer.ledger_timestamp IS
    'TigerBeetle''s u64 nanosecond commit timestamp. The checkpoint (projection_checkpoint) advances on this; it is the audit/replay ordering key.';
COMMENT ON COLUMN projection.transfer.ledger_id IS
    '484 = MXN cash cents; 1001+ = one ledger per fund, units at 1e-8 (LedgerCodes, chart-of-accounts.md §1).';
COMMENT ON COLUMN projection.transfer.amount_scaled IS
    'The ledger leg''s own positive magnitude (cents, or units at 1e-8). Direction is debit vs credit account, never the sign here.';
COMMENT ON COLUMN projection.transfer.booking_date IS
    'The business date of the ledger timestamp -- audit ordering and cursors (O-4).';
COMMENT ON COLUMN projection.transfer.value_date IS
    'The economic date the movement belongs to. Defaults to booking_date for ordinary payments; a batch-originated posting (interest, fees, corrections) carries an explicit earlier date so it lands in the right close (O-4). Part of the PK and a partition key.';
COMMENT ON COLUMN projection.transfer.nav_version_id IS
    'Set on unit-allotment transfers: which reference.fund_nav row priced this issuance. No FK -- fund_nav is in another schema and this is a projection, not a rule store.';
COMMENT ON COLUMN projection.transfer.reference IS
    'Opaque external-reference string the ledger carried on the transfer (ExternalReference), for tie-out. May be null.';

-- DEFAULT partition: rows for any tenant without a dedicated partition. Itself
-- range-partitioned by month so the same monthly-leaf machinery applies before a
-- tenant is formally onboarded; `_overflow` catches anything outside a created month.
CREATE TABLE projection.transfer_default
    PARTITION OF projection.transfer DEFAULT
    PARTITION BY RANGE (value_date);

CREATE TABLE projection.transfer_default_overflow
    PARTITION OF projection.transfer_default DEFAULT;

CREATE INDEX transfer_default_ledger_timestamp
    ON projection.transfer_default (ledger_timestamp);

-- ---------------------------------------------------------------------------
-- participant_units -- sparse: a row only when a participant's holding changes.
-- ---------------------------------------------------------------------------
CREATE TABLE projection.participant_units (
    tenant_id             text          NOT NULL,
    participant_id        numeric(39,0) NOT NULL,
    fund_id               integer       NOT NULL,
    effective_date        date          NOT NULL,
    units_scaled          bigint        NOT NULL,
    last_transfer_id      numeric(39,0) NOT NULL,
    last_ledger_timestamp numeric(20,0) NOT NULL,
    prev_row_hash         bytea,
    row_hash              bytea         NOT NULL,

    CONSTRAINT participant_units_pk
        PRIMARY KEY (tenant_id, participant_id, fund_id, effective_date),
    CONSTRAINT participant_units_non_negative
        CHECK (units_scaled >= 0)
) PARTITION BY LIST (tenant_id);

COMMENT ON TABLE projection.participant_units IS
    'Sparse per-participant unit holding: one row per (participant, fund) only when the holding changes -- contribution, withdrawal or switch (docs/DOMAIN.md). Value on a quiet day is derived (last row x that day''s NAV), never stored. Append-only (Task 5.2).';
COMMENT ON COLUMN projection.participant_units.effective_date IS
    'The value date of the entry that changed the holding (O-4), never the booking date and never now(). "Units as-of D" is the latest row with effective_date <= D.';
COMMENT ON COLUMN projection.participant_units.units_scaled IS
    'Absolute units held after this change, scale 1e-8. Never negative (CHECK): units in issue cannot go below zero.';
COMMENT ON COLUMN projection.participant_units.last_transfer_id IS
    'The unit-ledger transfer that produced this holding. A u128 hash, NOT monotonic -- it cannot order events, which is why last_ledger_timestamp exists.';
COMMENT ON COLUMN projection.participant_units.last_ledger_timestamp IS
    'The ledger_timestamp of the entry that last wrote this row. Task 5.5''s upsert only overwrites when the incoming timestamp is strictly greater, so redelivery and out-of-order arrival are no-ops. Added to the sketch because last_transfer_id (a hash) cannot serve.';
COMMENT ON COLUMN projection.participant_units.row_hash IS
    'SHA256(prev_row_hash || participant_id || fund_id || effective_date || units_scaled || last_transfer_id), chained per (participant, fund) so the holding history is tamper-evident and recomputable (Task 5.5).';

CREATE TABLE projection.participant_units_default
    PARTITION OF projection.participant_units DEFAULT;

-- ---------------------------------------------------------------------------
-- projection_checkpoint -- the consumer's replay cursor. One row per projection.
-- ---------------------------------------------------------------------------
CREATE TABLE projection.projection_checkpoint (
    projection_name       text          NOT NULL,
    last_ledger_timestamp numeric(20,0) NOT NULL DEFAULT 0,
    updated_at            timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT projection_checkpoint_pk PRIMARY KEY (projection_name),
    CONSTRAINT projection_checkpoint_not_negative CHECK (last_ledger_timestamp >= 0)
);

COMMENT ON TABLE projection.projection_checkpoint IS
    'How far each projection consumer has processed, by ledger_timestamp. Advanced in the same Postgres transaction as the rows it covers (Task 5.4), so it never runs ahead of committed data. The one projection table that mutates in place -- Task 5.2 leaves it out of the append-only lock.';
COMMENT ON COLUMN projection.projection_checkpoint.last_ledger_timestamp IS
    'Highest ledger_timestamp durably projected. Advances monotonically and never skips (Task 5.4). A rebuild (Task 5.8) resets it to 0.';

-- The checkpoint is the sole projection table the CDC consumer may UPDATE. The
-- schema default (V1__schemas_and_grants.sql) grants fondaria_cdc only SELECT, INSERT
-- on projection tables; transfer and participant_units stay exactly that way.
GRANT UPDATE ON projection.projection_checkpoint TO fondaria_cdc;

-- ---------------------------------------------------------------------------
-- Partition management.
-- ---------------------------------------------------------------------------
-- Bookkeeping only: which tenants have a dedicated partition, and the hashed slug
-- used in the partition table names (tenant_id is arbitrary text; a table name is
-- not). `ensure_upcoming_transfer_months` iterates this to pre-create leaves.
CREATE TABLE projection.partitioned_tenant (
    tenant_id  text        NOT NULL,
    slug       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT partitioned_tenant_pk PRIMARY KEY (tenant_id),
    CONSTRAINT partitioned_tenant_slug_unique UNIQUE (slug)
);

COMMENT ON TABLE projection.partitioned_tenant IS
    'One row per tenant given dedicated transfer/participant_units partitions. Populated by projection.ensure_tenant_partitions. slug = left(md5(tenant_id), 16), the safe identifier fragment used in partition names.';

-- Create a tenant's dedicated partitions. Call at tenant onboarding, BEFORE any
-- rows for that tenant arrive: Postgres refuses to carve a LIST partition out of a
-- DEFAULT that already holds matching rows. Idempotent.
CREATE FUNCTION projection.ensure_tenant_partitions(p_tenant_id text)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_slug         text := left(md5(p_tenant_id), 16);
    v_transfer     text := 'transfer_t_' || v_slug;
    v_units        text := 'participant_units_t_' || v_slug;
BEGIN
    IF p_tenant_id IS NULL OR length(p_tenant_id) = 0 THEN
        RAISE EXCEPTION 'tenant id must be a non-empty string';
    END IF;

    INSERT INTO projection.partitioned_tenant (tenant_id, slug)
    VALUES (p_tenant_id, v_slug)
    ON CONFLICT (tenant_id) DO NOTHING;

    IF to_regclass('projection.' || quote_ident(v_transfer)) IS NULL THEN
        EXECUTE format(
            'CREATE TABLE projection.%I PARTITION OF projection.transfer '
            || 'FOR VALUES IN (%L) PARTITION BY RANGE (value_date)',
            v_transfer, p_tenant_id);
        EXECUTE format(
            'CREATE TABLE projection.%I PARTITION OF projection.%I DEFAULT',
            v_transfer || '_overflow', v_transfer);
        EXECUTE format(
            'CREATE INDEX %I ON projection.%I (ledger_timestamp)',
            v_transfer || '_ts', v_transfer);
    END IF;

    IF to_regclass('projection.' || quote_ident(v_units)) IS NULL THEN
        EXECUTE format(
            'CREATE TABLE projection.%I PARTITION OF projection.participant_units '
            || 'FOR VALUES IN (%L)',
            v_units, p_tenant_id);
    END IF;
END;
$$;

-- Ensure the monthly transfer leaf covering p_month exists, for one tenant
-- (p_tenant_id NULL => the shared DEFAULT partition). p_month is truncated to the
-- first of its month. Idempotent.
CREATE FUNCTION projection.ensure_transfer_month(p_tenant_id text, p_month date)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_month  date := date_trunc('month', p_month)::date;
    v_next   date := (date_trunc('month', p_month) + interval '1 month')::date;
    v_parent text;
    v_leaf   text;
BEGIN
    IF p_tenant_id IS NULL THEN
        v_parent := 'transfer_default';
    ELSE
        PERFORM projection.ensure_tenant_partitions(p_tenant_id);
        v_parent := 'transfer_t_' || left(md5(p_tenant_id), 16);
    END IF;

    v_leaf := v_parent || '_' || to_char(v_month, 'YYYYMM');

    IF to_regclass('projection.' || quote_ident(v_leaf)) IS NULL THEN
        EXECUTE format(
            'CREATE TABLE projection.%I PARTITION OF projection.%I '
            || 'FOR VALUES FROM (%L) TO (%L)',
            v_leaf, v_parent, v_month, v_next);
    END IF;
END;
$$;

-- Pre-create the monthly transfer leaves from p_from through p_ahead months out,
-- for the shared DEFAULT partition and every tenant in projection.partitioned_tenant.
-- Intended for a scheduled maintenance job, which passes its own business date
-- rather than relying on the wall clock (the demo compresses time). Idempotent.
CREATE FUNCTION projection.ensure_upcoming_transfer_months(
        p_from date DEFAULT current_date,
        p_ahead integer DEFAULT 3)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_offset integer;
    v_month  date;
    v_tenant text;
BEGIN
    FOR v_offset IN 0..greatest(p_ahead, 0) LOOP
        v_month := (date_trunc('month', p_from) + (v_offset || ' months')::interval)::date;
        PERFORM projection.ensure_transfer_month(NULL, v_month);
        FOR v_tenant IN SELECT tenant_id FROM projection.partitioned_tenant LOOP
            PERFORM projection.ensure_transfer_month(v_tenant, v_month);
        END LOOP;
    END LOOP;
END;
$$;

-- EXECUTE defaults to PUBLIC in Postgres. These functions run DDL as their owner,
-- so gate them to the CDC consumer's role explicitly.
REVOKE EXECUTE ON FUNCTION
    projection.ensure_tenant_partitions(text),
    projection.ensure_transfer_month(text, date),
    projection.ensure_upcoming_transfer_months(date, integer)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION
    projection.ensure_tenant_partitions(text),
    projection.ensure_transfer_month(text, date),
    projection.ensure_upcoming_transfer_months(date, integer)
    TO fondaria_cdc;

-- Seed the current and next three months on the DEFAULT partition, so an
-- freshly-migrated database can project today's movements without a maintenance
-- pass having run yet. Uses the migration wall clock deliberately: this is the
-- one-time floor, and the scheduled job takes over from here with an injected date.
SELECT projection.ensure_upcoming_transfer_months(current_date, 3);

-- Task 9.5 — Participant onboarding and identity data.
--
-- Reverses phase-02b's "participants are pseudonymous in v1" scope decision
-- (BACKLOG.md "Settled", V6__participant.sql's own header) on the platform
-- owner's explicit call: phase-09-identity.md Task 9.5 requires RFC, CURP,
-- name and date of birth to exist now, encrypted per participant, so that
-- destroying one participant's key crypto-shreds their identity without
-- touching anyone else's. Phase 16's onboarding/KYC flow remains the eventual
-- home for a fuller identity-capture experience; this table and its
-- envelope-encryption mechanism are what it builds on, not a duplicate of it.
--
-- ENVELOPE ENCRYPTION, NOT A SINGLE MASTER KEY DIRECTLY ON THE DATA. Each
-- participant gets their own randomly generated 256-bit data key (DEK), and
-- that DEK — not the master key — is what encrypts rfc/curp/name/date_of_birth
-- (AES-256-GCM, a random 12-byte IV prefixed to each ciphertext). The DEK
-- itself is encrypted ("wrapped") under one master key (KEK) held outside the
-- database entirely: PARTICIPANT_IDENTITY_MASTER_KEY, sourced from a
-- Kubernetes Secret, never committed, never defaulted in production (AGENTS.md
-- §4). Crypto-shredding a participant means destroying only THEIR
-- wrapped_data_key: with it gone, no key anywhere (not even the KEK) can ever
-- recover their DEK, so their four ciphertext columns become permanently
-- unreadable while every other participant's row — and every ledger balance
-- and position, which this table never touches — is untouched. Wrapping the
-- DEK under the KEK, rather than encrypting every field with the KEK
-- directly, is what makes that single-row, per-participant erasure possible
-- without ever rotating the KEK to shred one person.

CREATE TABLE master.participant_identity (
    organization_id             uuid        NOT NULL,
    participant_internal_ref    text        NOT NULL,
    wrapped_data_key            bytea,
    encrypted_rfc               bytea       NOT NULL,
    encrypted_curp              bytea       NOT NULL,
    encrypted_name              bytea       NOT NULL,
    encrypted_date_of_birth     bytea,
    date_of_birth_required      boolean     NOT NULL,
    identity_captured_as_of     date        NOT NULL,
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL,
    key_destroyed_at            timestamptz,

    CONSTRAINT participant_identity_pk
        PRIMARY KEY (organization_id, participant_internal_ref),
    CONSTRAINT participant_identity_participant_fk
        FOREIGN KEY (organization_id, participant_internal_ref)
        REFERENCES master.participant (organization_id, participant_internal_ref),
    CONSTRAINT participant_identity_key_state
        -- Either the wrapped key is present and destroyed_at is null (readable), or the key has
        -- been crypto-shredded and destroyed_at records when -- never both, never neither.
        CHECK ((wrapped_data_key IS NOT NULL AND key_destroyed_at IS NULL)
            OR (wrapped_data_key IS NULL AND key_destroyed_at IS NOT NULL)),
    CONSTRAINT participant_identity_dob_required_consistent
        -- If the rule evaluation at capture time said date of birth was required, it must actually
        -- be present -- this row is the only record of what the rule decided (AGENTS.md §4: every
        -- rule evaluation stores its inputs and outcome), so the two can never silently disagree.
        CHECK (NOT date_of_birth_required OR encrypted_date_of_birth IS NOT NULL)
);

COMMENT ON TABLE master.participant_identity IS
    'Encrypted participant identity fields (Task 9.5): RFC, CURP, name and date of birth, each AES-256-GCM encrypted under a per-participant data key that is itself wrapped under PARTICIPANT_IDENTITY_MASTER_KEY. Holds no plaintext identity value and is never queried or joined on one.';
COMMENT ON COLUMN master.participant_identity.wrapped_data_key IS
    'The per-participant AES-256 data key, itself AES-256-GCM encrypted under the master key (12-byte IV prefixed to ciphertext). NULL once crypto-shredded -- see key_destroyed_at.';
COMMENT ON COLUMN master.participant_identity.encrypted_rfc IS
    'AES-256-GCM ciphertext (12-byte IV prefixed) of the participant''s RFC under their own data key. Permanently undecryptable once wrapped_data_key is destroyed.';
COMMENT ON COLUMN master.participant_identity.encrypted_curp IS
    'AES-256-GCM ciphertext (12-byte IV prefixed) of the participant''s CURP under their own data key.';
COMMENT ON COLUMN master.participant_identity.encrypted_name IS
    'AES-256-GCM ciphertext (12-byte IV prefixed) of the participant''s legal name under their own data key.';
COMMENT ON COLUMN master.participant_identity.encrypted_date_of_birth IS
    'AES-256-GCM ciphertext (12-byte IV prefixed) of the participant''s date of birth (ISO-8601) under their own data key. NULL where the participant''s plan resolves to a non-retirement vehicle -- not required there (phase-09-identity.md Task 9.5).';
COMMENT ON COLUMN master.participant_identity.date_of_birth_required IS
    'Whether the rule evaluated at capture time (the participant''s plan''s fund vehicle, resolved as of identity_captured_as_of) required a date of birth. Stored so the decision is reproducible from this row alone years later, without re-running current code or current fund configuration (AGENTS.md §4).';
COMMENT ON COLUMN master.participant_identity.identity_captured_as_of IS
    'The date the plan/fund resolution in date_of_birth_required was evaluated as of -- the input to that rule, alongside the plan itself.';
COMMENT ON COLUMN master.participant_identity.key_destroyed_at IS
    'When this participant''s data key was crypto-shredded (right-to-erasure). Positions and ledger balances are untouched -- only this table''s four ciphertext columns become permanently unreadable.';

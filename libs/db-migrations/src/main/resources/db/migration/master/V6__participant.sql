-- Task 2b.3 (part two) — master.participant, populated by auto-enrolment.
--
-- Pseudonymous: an internal ref, a payroll ref, the two employment dates and
-- the statuses that govern them. No name, RFC or CURP — Phase 16, after
-- Phase 13.5's crypto-shredding (docs/ENGINEERING-RULES.md §1, phase-02b "Scope decisions
-- taken for v1").
--
-- The primary key is (organization_id, participant_internal_ref) rather than
-- a surrogate id: the internal ref is already stable, never reused and never
-- reassigned (contribution-file-v2.md §3), and every table here is tenant
-- scoped by organization_id first (docs/ENGINEERING-RULES.md §4). That primary key is also
-- what makes auto-enrolment idempotent: a row naming a participant already
-- known collides on INSERT and is matched instead of duplicated, whether the
-- second sighting is a re-swept batch or a later payroll period.

CREATE TABLE master.participant (
    organization_id           uuid        NOT NULL REFERENCES master.organization (organization_id),
    participant_internal_ref  text        NOT NULL,
    plan_id                   text        NOT NULL,
    participant_payroll_ref   text        NOT NULL,
    participant_status        text        NOT NULL,
    employment_status         text        NOT NULL,
    employment_start_date     date        NOT NULL,
    enrolment_date            date        NOT NULL,
    employment_end_date       date,
    created_at                timestamptz NOT NULL,
    updated_at                timestamptz NOT NULL,

    CONSTRAINT participant_pk
        PRIMARY KEY (organization_id, participant_internal_ref),
    CONSTRAINT participant_payroll_ref_unique_per_organization
        -- contribution-file-v2.md §3: unique within an organization, never
        -- globally. Two employers may legitimately share a payroll ref.
        UNIQUE (organization_id, participant_payroll_ref),
    CONSTRAINT participant_status_known
        CHECK (participant_status IN ('ACTIVE', 'DORMANT', 'CLOSED')),
    CONSTRAINT participant_employment_status_known
        CHECK (employment_status IN ('ACTIVE', 'TERMINATED')),
    CONSTRAINT participant_enrolment_not_before_employment
        -- The same rule the file parser already refuses at (MALFORMED_FILE):
        -- restated here so it holds regardless of which caller writes the row.
        CHECK (enrolment_date >= employment_start_date)
);

COMMENT ON TABLE master.participant IS
    'Pseudonymous participant records (Task 2b.3). Created by auto-enrolment from a contribution file''s rows; never carries a name, RFC or CURP.';
COMMENT ON COLUMN master.participant.participant_internal_ref IS
    'The platform''s stable reference for this participant. Never reused, never reassigned (contribution-file-v2.md §3). Not logged (docs/ENGINEERING-RULES.md §7) even though it carries no name — it still identifies a person.';
COMMENT ON COLUMN master.participant.participant_payroll_ref IS
    'The payroll system''s own reference, carried alongside the internal ref so a re-mapping never requires re-keying history (Task 2b.6). Unique within an organization, never globally.';
COMMENT ON COLUMN master.participant.employment_start_date IS
    'When the participant joined the organization — the seniority clock a graded vesting schedule runs off (ADR-0004). Distinct from enrolment_date and never collapsed into it (phase-02b Task 2b.3).';
COMMENT ON COLUMN master.participant.enrolment_date IS
    'When the participant joined the plan. Routinely later than employment_start_date; conflating the two misallocates employer money at withdrawal.';
COMMENT ON COLUMN master.participant.employment_end_date IS
    'Null while active. Set by the employment lifecycle (Task 2b.5), not by enrolment.';

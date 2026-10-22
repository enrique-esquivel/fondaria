-- Step 5 (BACKLOG.md) — Thin Task 2b.2: reference.plan_version.
--
-- Effective-dated and append-only: reference's schema-level default
-- privileges (V1__schemas_and_grants.sql) grant SELECT and INSERT on every
-- table here and never grant UPDATE or DELETE, so a correction is a new row
-- by construction, not by discipline. No table-level grant is added here —
-- the schema default already covers it.
--
-- "Thin" on purpose (BACKLOG.md step 5): a real plan version also carries
-- contribution percentages, caps, withdrawal rules, eligible funds and a fee
-- schedule (phase-02b Task 2b.2), none of which this table stores yet. It
-- carries only the employer matching ratio -- the one rule step 5 enforces
-- -- and the vesting schedule, which is versioned configuration per
-- ADR-0004 ("A vesting schedule is per-organization, effective-dated
-- configuration") and docs/ENGINEERING-RULES.md §4's ban on treating plan rules as code
-- constants, even though nothing in this step enforces it against a
-- contribution row.
--
-- plan_id is free text, matching workflow.contribution_batch.plan_id and
-- master.participant.plan_id -- no plan registry exists yet, so there is
-- nothing to foreign-key against.

CREATE TABLE reference.plan_version (
    organization_id                       uuid        NOT NULL REFERENCES master.organization (organization_id),
    plan_id                                text        NOT NULL,
    effective_from                        date        NOT NULL,
    version                               integer     NOT NULL,
    employer_match_ratio_numerator        integer     NOT NULL,
    employer_match_ratio_denominator      integer     NOT NULL,
    vesting_full_years                    integer     NOT NULL,
    published_at                          timestamptz NOT NULL,

    CONSTRAINT plan_version_pk
        PRIMARY KEY (organization_id, plan_id, effective_from, version),
    CONSTRAINT plan_version_ratio_positive
        CHECK (employer_match_ratio_numerator > 0 AND employer_match_ratio_denominator > 0),
    CONSTRAINT plan_version_vesting_full_years_non_negative
        CHECK (vesting_full_years >= 0)
);

COMMENT ON TABLE reference.plan_version IS
    'Effective-dated plan rulebook versions (phase-02b Task 2b.2, "thin" per BACKLOG.md step 5). Resolution is always as-of a date: the highest (effective_from, version) not after the date asked for.';
COMMENT ON COLUMN reference.plan_version.version IS
    'Disambiguates two versions published with the same effective_from -- a correction of a mistaken publication. The higher value wins resolution; the lower one remains readable (Task 2b.2 acceptance criterion 3).';
COMMENT ON COLUMN reference.plan_version.employer_match_ratio_numerator IS
    'Together with employer_match_ratio_denominator, the ratio an employer''s contribution must bear to the matching employee contribution on the same row (e.g. 1/1 for a dollar-for-dollar match). Enforced by ContributionBatchValidator via MatchingRatioRule.';
COMMENT ON COLUMN reference.plan_version.vesting_full_years IS
    'Years of continuous service, measured from a participant''s employment start date, at which a linear graded vesting schedule reaches 100%. Zero means immediate vesting. Not enforced against any contribution or withdrawal this step -- read only by VestingScheduleCalculator for the acceptance-criterion-5 assertion.';

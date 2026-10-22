-- Task 21.1 — when a batch became dealable (phase-21-allotment.md).
--
-- WHY A COLUMN AND NOT status_updated_at. Cut-off is evaluated against the moment
-- a batch became ready to allot: BusinessCalendar.businessDateOf(staged_at) is the
-- earliest valuation date it may deal on, and a batch one second either side of
-- cut-off deals on different dates. status_updated_at answers a different question
-- -- "when did this row last change" -- and any future status write would move it,
-- silently re-dating a batch that has been waiting to deal since Tuesday. A dealing
-- date that changes because an unrelated column changed is the kind of defect that
-- surfaces as a participant priced at the wrong NAV.
--
-- WRITE-ONCE. Set the first time a batch reaches STAGED and never rewritten:
-- JdbcContributionBatchRepository.resolve writes COALESCE(staged_at, :stagedAt).
-- A re-resolve by a later sweep therefore cannot re-date an unallotted batch.
--
-- NULLABLE, DELIBERATELY. Flyway expand/contract (AGENTS.md §4): the previous
-- application version writes STAGED without this column, so a NOT NULL or a CHECK
-- tying it to status would stop that version dead. Rows staged before this
-- migration carry NULL and report no dealing date -- honest, and Phase 21 has
-- allotted nothing yet, so no such batch has been priced on a date it did not have.

ALTER TABLE workflow.contribution_batch
    ADD COLUMN staged_at timestamptz;

COMMENT ON COLUMN workflow.contribution_batch.staged_at IS
    'When this batch first reached STAGED -- the moment it became dealable. Write-once: the cut-off test (Task 21.1) reads it, so it must not move. NULL for rows staged before this column existed, and for every batch that has not staged.';

-- The pending-allotment sweep: dealable batches, oldest first. Partial, because
-- STAGED is the only status this query ever asks for and the index has no business
-- carrying every RECEIVED and REJECTED row a tenant has ever submitted.
CREATE INDEX contribution_batch_staged_by_organization
    ON workflow.contribution_batch (organization_id, staged_at)
    WHERE status = 'STAGED';

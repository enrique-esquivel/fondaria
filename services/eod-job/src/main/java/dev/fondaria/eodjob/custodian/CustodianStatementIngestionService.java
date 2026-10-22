package dev.fondaria.eodjob.custodian;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Records a provider statement, idempotent on {@code (providerAccountId, businessDate)}.
 *
 * <p>A resubmission with the same checksum is the same file arriving twice and returns the row
 * already on record rather than inserting a second one. A resubmission with a different checksum is
 * refused ({@link StatementConflictException}) — Task 7.3 names no correction path for a statement,
 * so a live one is never silently replaced.
 */
@Service
public class CustodianStatementIngestionService {

  private final CustodianStatementRepository statements;
  private final Clock clock;

  public CustodianStatementIngestionService(CustodianStatementRepository statements, Clock clock) {
    this.statements = Objects.requireNonNull(statements, "statements must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public CustodianStatement submit(CustodianStatementSubmission submission) {
    Objects.requireNonNull(submission, "submission must not be null");
    Optional<CustodianStatement> existing =
        statements.findFor(submission.providerAccountId(), submission.businessDate());
    if (existing.isPresent()) {
      if (existing.get().checksumSha256().equals(submission.checksumSha256())) {
        return existing.get();
      }
      throw new StatementConflictException(
          submission.providerAccountId(), submission.businessDate());
    }
    return statements.insert(java.util.UUID.randomUUID(), submission, clock.instant());
  }
}

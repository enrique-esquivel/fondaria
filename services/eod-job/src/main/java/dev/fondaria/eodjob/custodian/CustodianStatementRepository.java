package dev.fondaria.eodjob.custodian;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface CustodianStatementRepository {

  Optional<CustodianStatement> findFor(UUID providerAccountId, LocalDate businessDate);

  /** Inserts a new statement. Callers resolve idempotency/conflict before calling this. */
  CustodianStatement insert(
      UUID statementId, CustodianStatementSubmission submission, Instant receivedAt);
}

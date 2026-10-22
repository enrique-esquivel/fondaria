package dev.fondaria.eodjob.custodian;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface CustodianReconciliationRepository {

  void open(UUID runId, LocalDate businessDate, Instant startedAt);

  void complete(CustodianReconciliationRun run);

  void fail(UUID runId, Instant failedAt, long durationMillis);

  /**
   * The latest run to *settle* for this date — see {@code JdbcReconciliationRepository} (Task 7.2)
   * for why settlement order, not start order, is what a concurrent-safe gate must read.
   */
  Optional<CustodianReconciliationRun> latestFor(LocalDate businessDate);
}

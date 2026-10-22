package dev.fondaria.valuationapi.ingestion;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * A transaction manager that starts and ends nothing, so a {@code TransactionTemplate} can run its
 * callback inline in a unit test. Mirrors {@code contribution-api}'s own of the same name
 * deliberately rather than sharing it: each lives with the suite that relies on it.
 *
 * <p>Rollback is not simulated, and {@link ValuationIngestionRunnerTest} is written knowing it — it
 * exercises what happens <i>after</i> a successful commit, not the commit boundary itself, which
 * {@code ValuationIngestionIntegrationTest} proves against a real PostgreSQL.
 */
final class DirectTransactionManager implements PlatformTransactionManager {

  @Override
  public TransactionStatus getTransaction(TransactionDefinition definition) {
    return new SimpleTransactionStatus();
  }

  @Override
  public void commit(TransactionStatus status) {}

  @Override
  public void rollback(TransactionStatus status) {}
}

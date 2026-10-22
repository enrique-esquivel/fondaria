package dev.fondaria.contribution.batch;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * A transaction manager that starts and ends nothing, so a {@code TransactionTemplate} can run its
 * callback inline in a unit test. Mirrors the cash-receipt tests' own, deliberately rather than
 * shared: each lives with the suite that relies on it, and neither task owns the other's files.
 *
 * <p>Rollback is not simulated, and the tests are written knowing it. The one place rollback
 * matters — a claim that must go back when the batch's status could not be written — is asserted
 * against a real PostgreSQL in {@code ContributionBatchIntegrationTest}, because a fake that cannot
 * roll back could not tell that story either way.
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

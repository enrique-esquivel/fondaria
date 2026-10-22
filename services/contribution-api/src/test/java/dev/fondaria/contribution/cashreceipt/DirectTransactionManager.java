package dev.fondaria.contribution.cashreceipt;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * A transaction manager that starts and ends nothing, so a {@code TransactionTemplate} can run its
 * callback inline in a unit test.
 *
 * <p>Rollback is not simulated, and the tests are written knowing it: the one place rollback
 * matters — a rejection whose {@code REJECTED} status must survive being reported — is asserted
 * against a real PostgreSQL in {@code CashReceiptIntegrationTest}, because a fake that cannot roll
 * back could not tell that story either way.
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

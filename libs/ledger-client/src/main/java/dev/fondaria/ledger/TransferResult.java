package dev.fondaria.ledger;

import java.time.Instant;
import java.util.Objects;

/** The outcome of one {@link CreateTransferCommand}. */
public sealed interface TransferResult
    permits TransferResult.Created, TransferResult.AlreadyExists, TransferResult.Rejected {

  TransferId id();

  /** The transfer was newly created and posted (or reserved, for a {@code PENDING} transfer). */
  record Created(TransferId id, Instant timestamp) implements TransferResult {
    public Created {
      Objects.requireNonNull(id, "id must not be null");
      Objects.requireNonNull(timestamp, "timestamp must not be null");
    }
  }

  /** A transfer with this id was already created; nothing moved a second time. */
  record AlreadyExists(TransferId id) implements TransferResult {
    public AlreadyExists {
      Objects.requireNonNull(id, "id must not be null");
    }
  }

  /** The ledger refused the transfer; nothing moved. */
  record Rejected(TransferId id, LedgerRejectionReason reason) implements TransferResult {
    public Rejected {
      Objects.requireNonNull(id, "id must not be null");
      Objects.requireNonNull(reason, "reason must not be null");
    }
  }
}

package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TransferResultTest {

  private static final TransferId ID = TransferId.of(1L, 1L);

  @Test
  void createdAndAlreadyExistsAndRejectedAreTheOnlyPermittedVariants() {
    TransferResult created = new TransferResult.Created(ID, Instant.EPOCH);
    TransferResult alreadyExists = new TransferResult.AlreadyExists(ID);
    TransferResult rejected =
        new TransferResult.Rejected(ID, LedgerRejectionReason.INSUFFICIENT_FUNDS);

    // Exhaustive switch compiles without a default branch only because these three records are
    // the sealed interface's entire permitted set.
    for (TransferResult result : new TransferResult[] {created, alreadyExists, rejected}) {
      String description =
          switch (result) {
            case TransferResult.Created c -> "created";
            case TransferResult.AlreadyExists a -> "already-exists";
            case TransferResult.Rejected r -> "rejected:" + r.reason();
          };
      assertThat(description).isNotBlank();
    }
  }

  @Test
  void everyDocumentedRejectionReasonHasAnUnknownFallback() {
    assertThat(LedgerRejectionReason.values()).contains(LedgerRejectionReason.UNKNOWN);
  }
}

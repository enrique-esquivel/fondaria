package dev.fondaria.eodjob.custodian;

import java.io.Serial;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A statement for a {@code (providerAccountId, businessDate)} already on record was submitted again
 * with a different checksum.
 *
 * <p>Refused rather than resolved — mirroring {@code JdbcAcceptedValuationRepository}'s refusal of
 * a differing valuation. Task 7.3 names no statement correction or supersession path, so guessing
 * at one here would invent a decision nobody made.
 */
public final class StatementConflictException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final UUID providerAccountId;
  private final LocalDate businessDate;

  public StatementConflictException(UUID providerAccountId, LocalDate businessDate) {
    super(
        "a statement already exists for providerAccountId="
            + providerAccountId
            + " businessDate="
            + businessDate
            + " with a different checksum; correction/supersession is not built for custodian"
            + " statements");
    this.providerAccountId = Objects.requireNonNull(providerAccountId);
    this.businessDate = Objects.requireNonNull(businessDate);
  }

  public UUID providerAccountId() {
    return providerAccountId;
  }

  public LocalDate businessDate() {
    return businessDate;
  }
}

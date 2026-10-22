package dev.fondaria.valuationapi.web;

import dev.fondaria.valuationapi.ingestion.ValuationBatch;
import dev.fondaria.valuationapi.ingestion.ValuationRejection;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A batch's status resource.
 *
 * <p><b>The checksum is not rendered.</b> The submitter already has it, and echoing it adds nothing
 * a caller can act on.
 */
public record ValuationBatchResponse(
    UUID batchId,
    String source,
    String objectKey,
    String status,
    int declaredRowCount,
    Integer acceptedValuationCount,
    RejectionResponse rejection,
    Instant submittedAt,
    Instant statusUpdatedAt) {

  /** {@code null} rather than an empty object when the batch was not rejected. */
  public record RejectionResponse(String code, String message, Map<String, Object> details) {

    static RejectionResponse of(ValuationRejection rejection) {
      return new RejectionResponse(
          rejection.code().name(), rejection.message(), rejection.details());
    }
  }

  public static ValuationBatchResponse of(ValuationBatch batch) {
    return new ValuationBatchResponse(
        batch.batchId(),
        batch.source(),
        batch.objectKey(),
        batch.status().name(),
        batch.declaredRowCount(),
        batch.acceptedValuationCount().isPresent()
            ? batch.acceptedValuationCount().getAsInt()
            : null,
        batch.rejection().map(RejectionResponse::of).orElse(null),
        batch.submittedAt(),
        batch.statusUpdatedAt());
  }
}

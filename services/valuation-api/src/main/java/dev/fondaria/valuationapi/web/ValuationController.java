package dev.fondaria.valuationapi.web;

import dev.fondaria.valuationapi.ageing.ValuationAgeingService;
import dev.fondaria.valuationapi.ingestion.InvalidValuationSubmissionException;
import dev.fondaria.valuationapi.ingestion.ValuationBatch;
import dev.fondaria.valuationapi.ingestion.ValuationBatchService;
import dev.fondaria.valuationapi.objectstore.PresignedUpload;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two calls of the ingestion transport, the batch's status resource, and the ageing report.
 *
 * <h2>This endpoint ships unauthenticated, and that is a known gap</h2>
 *
 * <p>Same position as the contribution and cash-receipt endpoints: authorization is Phase 9's
 * (identity) and Phase 10's (edge), and no scheme is invented here because inventing one would have
 * to be removed. Until then an object key is scoped to its source by prefix, so one provider cannot
 * submit another's file even without authentication — but the endpoints must not be exposed outside
 * the cluster.
 *
 * <h2>Status codes</h2>
 *
 * <ul>
 *   <li>{@code 200} — an upload slot, a batch status, or the ageing report
 *   <li>{@code 202} — batch received; validation is asynchronous. Also returned for a resubmission
 *       of the same file, carrying the original batch and its current status
 *   <li>{@code 400} — the call is unusable (bad checksum format, foreign object key)
 *   <li>{@code 404} — no such batch
 * </ul>
 *
 * <p>Every binding annotation names its parameter explicitly. The build does not compile with
 * {@code -parameters}, so an unnamed {@code @PathVariable} resolves to nothing at runtime and fails
 * every request it appears on.
 */
@RestController
@RequestMapping("/v1")
public class ValuationController {

  private final ValuationBatchService batches;
  private final ValuationAgeingService ageing;

  public ValuationController(ValuationBatchService batches, ValuationAgeingService ageing) {
    this.batches = batches;
    this.ageing = ageing;
  }

  @PostMapping("/valuation-batches/upload-url")
  public ResponseEntity<ValuationUploadUrlResponse> createUploadUrl(
      @RequestBody ValuationUploadUrlRequest request) {
    PresignedUpload upload =
        batches.createUploadSlot(request.requireSource(), request.requireFileName());
    return ResponseEntity.ok(ValuationUploadUrlResponse.of(upload));
  }

  /**
   * {@code 202} whether this call created the batch or found it. Deliberately not {@code 201} on
   * first submission and {@code 200} on a repeat: nothing has been validated either way, so a
   * status code claiming a resource was created would be claiming something this call has not
   * established.
   */
  @PostMapping("/valuation-batches")
  public ResponseEntity<ValuationBatchResponse> submit(
      @RequestBody SubmitValuationBatchRequest request) {
    ValuationBatch batch =
        batches.submit(
            request.requireSource(),
            request.requireObjectKey(),
            request.requireChecksum(),
            request.requireDeclaredRowCount());
    return ResponseEntity.accepted()
        .location(URI.create("/v1/valuation-batches/" + batch.batchId()))
        .body(ValuationBatchResponse.of(batch));
  }

  @GetMapping("/valuation-batches/{batchId}")
  public ValuationBatchResponse get(@PathVariable("batchId") String batchId) {
    return ValuationBatchResponse.of(batches.get(parseBatchId(batchId)));
  }

  /**
   * Business dates with no accepted valuation. Platform-wide rather than per-tenant: a fund is not
   * owned by one employer, and a stale NAV is stale for every participant in it.
   */
  @GetMapping("/valuations/ageing")
  public ValuationAgeingResponse ageingReport(
      @RequestParam(name = "asOf", required = false) String asOf) {
    return ValuationAgeingResponse.of(
        asOf == null || asOf.isBlank() ? ageing.report() : ageing.reportAsOf(parseAsOf(asOf)));
  }

  private static UUID parseBatchId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new InvalidValuationSubmissionException("batchId is not a valid UUID");
    }
  }

  private static LocalDate parseAsOf(String raw) {
    try {
      return LocalDate.parse(raw.trim());
    } catch (DateTimeParseException e) {
      throw new InvalidValuationSubmissionException("asOf must be a YYYY-MM-DD date");
    }
  }
}

package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.FundId;
import dev.fondaria.valuationapi.ingestion.ValuationBatch;
import dev.fondaria.valuationapi.ingestion.ValuationBatchRepository;
import dev.fondaria.valuationapi.ingestion.ValuationBatchService;
import dev.fondaria.valuationapi.ingestion.ValuationBatchStatus;
import dev.fondaria.valuationapi.ingestion.ValuationFileParser;
import dev.fondaria.valuationapi.ingestion.ValuationIngestionRunner;
import dev.fondaria.valuationapi.objectstore.PresignedUpload;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * One external investment platform delivering a valuation file, driven the way a provider drives it
 * (Task 20.3): ask {@code valuation-api} for an upload slot, {@code PUT} the bytes straight to
 * object storage, submit the object key and its checksum by reference, and let the asynchronous
 * validation sweep decide the outcome.
 *
 * <p><b>Nothing here writes {@code workflow.valuation} or {@code reference.fund_nav}.</b> That is
 * the whole point of this class: a price only exists in these tests because a file arrived, was
 * checksummed, parsed and validated, and was then struck. {@code Scenario}'s direct insert remains
 * for the Task 6.6 tests that need a price rather than a delivery.
 *
 * <p>The sweep is called rather than waited for — {@code VALUATION_POLL_INTERVAL} is an hour in
 * this stack, so nothing fires on a timer and no assertion here depends on a scheduler.
 */
final class ValuationDelivery {

  /** The provider. A constant: {@code source} scopes an object key, it identifies nothing else. */
  private static final String SOURCE = "ACME-CUSTODY";

  private final ValuationBatchService batches;
  private final ValuationIngestionRunner runner;
  private final ValuationBatchRepository batchRepository;

  ValuationDelivery(PlatformStack stack) {
    this.batches = stack.valuationApiBean(ValuationBatchService.class);
    this.runner = stack.valuationApiBean(ValuationIngestionRunner.class);
    this.batchRepository = stack.valuationApiBean(ValuationBatchRepository.class);
  }

  /** One fund's reported pooled balance on one date, in MXN cents. */
  record Row(FundId fundId, LocalDate valuationDate, long reportedValueCents) {

    /** {@code valuation-file-v1.md}'s four columns. A fresh provider reference every time. */
    String toCsv() {
      return fundId.value()
          + ","
          + valuationDate
          + ","
          + reportedValueCents
          + ",ACME-"
          + UUID.randomUUID();
    }
  }

  /**
   * Delivers one file and runs the validation sweep.
   *
   * @return the batch as ingestion left it — accepted, or rejected with a reason
   */
  ValuationBatch deliver(List<Row> rows) {
    String csv =
        ValuationFileParser.columnHeaderLine()
            + "\n"
            + String.join("\n", rows.stream().map(Row::toCsv).toList())
            + "\n";
    ValuationBatch submitted = batches.submit(SOURCE, upload(csv), sha256Hex(csv), rows.size());
    runner.sweep();
    return batchRepository.find(submitted.batchId()).orElseThrow();
  }

  /**
   * {@link #deliver(List)}, asserting the file was accepted rather than leaving it to the caller.
   */
  ValuationBatch deliverAccepted(List<Row> rows) {
    ValuationBatch batch = deliver(rows);
    assertThat(batch.status())
        .as("the provider's file was accepted: %s", batch.rejection())
        .isEqualTo(ValuationBatchStatus.ACCEPTED);
    return batch;
  }

  /** One row per date, each in its own file — a provider delivering daily, in date order. */
  List<ValuationBatch> deliverDaily(
      FundId fundId, List<LocalDate> dates, List<Long> reportedCents) {
    assertThat(reportedCents).as("one reported balance per date").hasSameSizeAs(dates);
    List<ValuationBatch> delivered = new ArrayList<>(dates.size());
    for (int i = 0; i < dates.size(); i++) {
      delivered.add(deliverAccepted(List.of(new Row(fundId, dates.get(i), reportedCents.get(i)))));
    }
    return List.copyOf(delivered);
  }

  /** Requests a slot and PUTs the bytes to it, exactly as a provider would. */
  private String upload(String csv) {
    PresignedUpload slot = batches.createUploadSlot(SOURCE, "acme-valuations.csv");
    assertThat(put(slot.url(), csv)).as("upload to the presigned slot").isEqualTo(200);
    return slot.objectKey();
  }

  private static int put(URI url, String content) {
    try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      return http.send(
              HttpRequest.newBuilder(url)
                  .timeout(Duration.ofSeconds(20))
                  .PUT(HttpRequest.BodyPublishers.ofString(content, StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.discarding())
          .statusCode();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted uploading the valuation file", e);
    } catch (Exception e) {
      throw new IllegalStateException("could not upload the valuation file", e);
    }
  }

  private static String sha256Hex(String content) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}

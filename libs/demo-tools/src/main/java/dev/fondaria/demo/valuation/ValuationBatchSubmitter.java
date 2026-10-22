package dev.fondaria.demo.valuation;

import dev.fondaria.demo.DemoToolsException;
import dev.fondaria.demo.Sha256;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Upload-slot, upload, submit, poll for a valuation file -- the valuation-side counterpart of
 * `ContributionBatchSubmitter`.
 */
public final class ValuationBatchSubmitter {

  private ValuationBatchSubmitter() {}

  public static Map<String, Object> uploadAndSubmit(
      ValuationApiClient client, String source, String fileName, String csv, int declaredRowCount) {
    Map<String, Object> slot = client.createUploadSlot(source, fileName);
    String objectKey = (String) slot.get("objectKey");
    String uploadUrl = (String) slot.get("uploadUrl");
    byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);

    int putStatus = client.uploadFile(uploadUrl, bytes);
    if (putStatus / 100 != 2) {
      throw new DemoToolsException("upload to presigned slot failed: HTTP " + putStatus);
    }

    String checksum = Sha256.hex(bytes);
    return client.submitValuationBatch(objectKey, checksum, source, declaredRowCount);
  }

  public static Map<String, Object> pollUntilTerminal(
      ValuationApiClient client, String batchId, Duration timeout, Duration pollInterval) {
    Instant deadline = Instant.now().plus(timeout);
    while (true) {
      Map<String, Object> batch = client.getValuationBatch(batchId).body();
      String status = (String) batch.get("status");
      if ("ACCEPTED".equals(status) || "REJECTED".equals(status)) {
        return batch;
      }
      if (Instant.now().isAfter(deadline)) {
        throw new DemoToolsException(
            "timed out waiting for valuation batch " + batchId + " (last status " + status + ")");
      }
      sleep(pollInterval);
    }
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DemoToolsException("interrupted while polling", e);
    }
  }
}

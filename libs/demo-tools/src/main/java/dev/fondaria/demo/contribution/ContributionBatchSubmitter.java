package dev.fondaria.demo.contribution;

import dev.fondaria.demo.DemoToolsException;
import dev.fondaria.demo.Sha256;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Upload-slot, upload, submit, poll -- the same four steps every command that submits a
 * contribution file repeats (`seed`, `contributions`, `onboard`), pulled out once
 * (`phase-D-demo.md` Task D.2).
 */
public final class ContributionBatchSubmitter {

  private ContributionBatchSubmitter() {}

  /** Uploads {@code csv} and submits it, returning the batch the submission call returned. */
  public static Map<String, Object> uploadAndSubmit(
      ContributionApiClient client,
      String organizationId,
      String planId,
      String payrollPeriod,
      LocalDate valueDate,
      String fileName,
      String csv,
      long declaredTotalCents,
      int declaredRowCount,
      Map<String, Long> declaredSourceCents) {
    Map<String, Object> slot =
        client.createUploadSlot(organizationId, planId, payrollPeriod, fileName);
    String objectKey = (String) slot.get("objectKey");
    String uploadUrl = (String) slot.get("uploadUrl");
    byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);

    int putStatus = client.uploadFile(uploadUrl, bytes);
    if (putStatus / 100 != 2) {
      throw new DemoToolsException("upload to presigned slot failed: HTTP " + putStatus);
    }

    String checksum = Sha256.hex(bytes);
    return client.submitContributionBatch(
        organizationId,
        objectKey,
        checksum,
        planId,
        payrollPeriod,
        valueDate,
        declaredTotalCents,
        declaredRowCount,
        declaredSourceCents);
  }

  /**
   * Polls {@code GET .../contribution-batches/{batchId}} until whole-batch validation has settled:
   * {@code REJECTED}, {@code STAGED}, or {@code ALLOTTED}. Used right after submission, before this
   * module cares whether allotment itself has happened yet (a batch legitimately rests at {@code
   * STAGED}, "awaiting allotment", until its dealing date's NAV is struck).
   *
   * <p>Does <b>not</b> treat {@code parts} appearing as settlement on its own: a file `ADR-0017`
   * divides into parts only after the whole file validates, but the resulting part's own status can
   * still be short of {@code STAGED} for a moment afterwards (e.g. its own reconciliation has not
   * caught up yet) -- returning early there would have this module quietly stop tracking an
   * organization whose batch never actually reached a state {@code seed}/{@code contributions} know
   * how to act on.
   */
  public static Map<String, Object> pollUntilSettled(
      ContributionApiClient client,
      String organizationId,
      String batchId,
      Duration timeout,
      Duration pollInterval) {
    return poll(
        client,
        organizationId,
        batchId,
        timeout,
        pollInterval,
        batch -> {
          String status = (String) batch.get("status");
          return "REJECTED".equals(status) || "STAGED".equals(status) || "ALLOTTED".equals(status);
        });
  }

  /**
   * Polls until the batch (or, for a file divided into parts, `ADR-0017`, every part of it) has
   * actually allotted: {@code REJECTED}, {@code ALLOTTED} for an unsplit batch, or {@code
   * parts.allottedParts == parts.partCount} for a split one.
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> pollUntilAllotted(
      ContributionApiClient client,
      String organizationId,
      String batchId,
      Duration timeout,
      Duration pollInterval) {
    return poll(
        client,
        organizationId,
        batchId,
        timeout,
        pollInterval,
        batch -> {
          if ("REJECTED".equals(batch.get("status"))) {
            return true;
          }
          Map<String, Object> parts = (Map<String, Object>) batch.get("parts");
          if (parts != null) {
            long partCount = ((Number) parts.get("partCount")).longValue();
            long allottedParts = ((Number) parts.get("allottedParts")).longValue();
            return allottedParts >= partCount;
          }
          return "ALLOTTED".equals(batch.get("status"));
        });
  }

  private static Map<String, Object> poll(
      ContributionApiClient client,
      String organizationId,
      String batchId,
      Duration timeout,
      Duration pollInterval,
      java.util.function.Predicate<Map<String, Object>> done) {
    Instant deadline = Instant.now().plus(timeout);
    while (true) {
      Map<String, Object> batch = client.getContributionBatch(organizationId, batchId).body();
      if (done.test(batch)) {
        return batch;
      }
      if (Instant.now().isAfter(deadline)) {
        throw new DemoToolsException(
            "timed out waiting for batch "
                + batchId
                + " (last status "
                + batch.get("status")
                + ")");
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

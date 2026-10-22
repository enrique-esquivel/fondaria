package dev.fondaria.contribution.batch.web;

import dev.fondaria.contribution.batch.InvalidBatchSubmissionException;

/**
 * Request body for an upload slot.
 *
 * <p>The plan and payroll period are here, not only on the submission, because the service mints
 * the object key from them ({@code contribution-file-v2.md} §1.1) — that is what keeps a stored
 * file identifiable on its own. The key they produce is also the key the submission must carry, so
 * a slot taken for one period cannot be submitted as another.
 *
 * @param fileName the producer's own file name. Carried through for audit and object-key naming
 *     only, never parsed for content and never trusted as a path — the service chooses the key.
 */
public record UploadUrlRequest(String planId, String payrollPeriod, String fileName) {

  private static final int MAXIMUM_FILE_NAME = 255;
  private static final int MAXIMUM_IDENTIFIER = 128;

  public String requireFileName() {
    return require(fileName, "fileName", MAXIMUM_FILE_NAME);
  }

  public String requirePlanId() {
    return require(planId, "planId", MAXIMUM_IDENTIFIER);
  }

  public String requirePayrollPeriod() {
    return require(payrollPeriod, "payrollPeriod", MAXIMUM_IDENTIFIER);
  }

  private static String require(String value, String field, int maximum) {
    if (value == null || value.isBlank()) {
      throw new InvalidBatchSubmissionException(field + " must not be blank");
    }
    if (value.length() > maximum) {
      throw new InvalidBatchSubmissionException(
          field + " must be at most " + maximum + " characters");
    }
    return value.trim();
  }
}

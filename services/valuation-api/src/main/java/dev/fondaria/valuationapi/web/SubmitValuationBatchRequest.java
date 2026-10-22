package dev.fondaria.valuationapi.web;

import dev.fondaria.valuationapi.ingestion.InvalidValuationSubmissionException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Submission body: where the file is, who sent it, and how many rows it should contain.
 *
 * <p>{@code source}, {@code objectKey} and {@code checksumSha256} are also the batch's identity,
 * which is why there is no {@code Idempotency-Key} header on this endpoint.
 *
 * <p><b>{@code declaredRowCount} is a JSON integer.</b> It is the only number on this request, and
 * the reported values themselves are integers of MXN cents inside the file — never a decimal and
 * never fractional (docs/ENGINEERING-RULES.md §4).
 *
 * @param checksumSha256 lowercase-hex SHA-256 of the exact bytes uploaded. Uppercase is accepted
 *     and folded down rather than rejected: the same bytes must be the same batch however a
 *     producer spells their digest, and two spellings of one checksum creating two batches would
 *     defeat the content-addressing scheme entirely.
 */
public record SubmitValuationBatchRequest(
    String objectKey, String checksumSha256, String source, Integer declaredRowCount) {

  private static final Pattern SHA256_HEX = Pattern.compile("^[a-f0-9]{64}$");
  private static final int MAXIMUM_OBJECT_KEY = 1024;
  private static final int MAXIMUM_IDENTIFIER = 128;

  public String requireObjectKey() {
    if (objectKey == null || objectKey.isBlank()) {
      throw new InvalidValuationSubmissionException("objectKey must not be blank");
    }
    if (objectKey.length() > MAXIMUM_OBJECT_KEY) {
      throw new InvalidValuationSubmissionException(
          "objectKey must be at most " + MAXIMUM_OBJECT_KEY + " characters");
    }
    return objectKey.trim();
  }

  public String requireChecksum() {
    String normalized =
        checksumSha256 == null ? "" : checksumSha256.trim().toLowerCase(Locale.ROOT);
    if (!SHA256_HEX.matcher(normalized).matches()) {
      throw new InvalidValuationSubmissionException(
          "checksumSha256 must be a 64-character hexadecimal SHA-256 digest");
    }
    return normalized;
  }

  public String requireSource() {
    if (source == null || source.isBlank()) {
      throw new InvalidValuationSubmissionException("source must not be blank");
    }
    if (source.length() > MAXIMUM_IDENTIFIER) {
      throw new InvalidValuationSubmissionException(
          "source must be at most " + MAXIMUM_IDENTIFIER + " characters");
    }
    return source.trim();
  }

  /**
   * A batch with no rows is not a batch. Refusing zero at the edge gives the provider a {@code 400}
   * naming the field, rather than a rejected batch whose file "must be empty".
   */
  public int requireDeclaredRowCount() {
    if (declaredRowCount == null) {
      throw new InvalidValuationSubmissionException("declaredRowCount must be present");
    }
    if (declaredRowCount <= 0) {
      throw new InvalidValuationSubmissionException("declaredRowCount must be greater than zero");
    }
    return declaredRowCount;
  }
}

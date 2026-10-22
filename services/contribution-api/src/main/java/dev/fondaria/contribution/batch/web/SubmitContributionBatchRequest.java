package dev.fondaria.contribution.batch.web;

import dev.fondaria.contribution.batch.BatchDeclaration;
import dev.fondaria.contribution.batch.ContributionSource;
import dev.fondaria.contribution.batch.InvalidBatchSubmissionException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Submission body: where the file is, and what the employer says it contains.
 *
 * <p>{@code objectKey} and {@code checksumSha256} are also the batch's identity, which is why there
 * is no {@code Idempotency-Key} header on this endpoint (ADR-0009 as amended).
 *
 * <p>Everything after them is the batch's own declaration ({@code contribution-file-v2.md} §1.1),
 * which v1 carried as a header row inside the file. It is typed and validated here, and the async
 * validator compares it against the rows that actually parsed.
 *
 * <p><b>Every amount is a JSON integer of MXN cents.</b> A decimal or a float would be the first
 * one in a money path (AGENTS.md §4), and a JSON number that is not an integer is rejected rather
 * than rounded.
 *
 * @param checksumSha256 lowercase-hex SHA-256 of the exact bytes uploaded. Uppercase is accepted
 *     and folded down rather than rejected: the same bytes must be the same batch however a
 *     producer spells their digest, and two spellings of one checksum creating two batches would
 *     defeat the whole content-addressing scheme.
 * @param declaredSourceCents one total per contribution source, keyed by its name. A source with
 *     nothing to contribute may declare {@code 0} or be omitted; omitted means zero, and the
 *     validator holds the file to it either way.
 */
public record SubmitContributionBatchRequest(
    String objectKey,
    String checksumSha256,
    String planId,
    String payrollPeriod,
    String valueDate,
    Long declaredTotalCents,
    Integer declaredRowCount,
    Map<String, Long> declaredSourceCents) {

  private static final Pattern SHA256_HEX = Pattern.compile("^[a-f0-9]{64}$");
  private static final int MAXIMUM_OBJECT_KEY = 1024;
  private static final int MAXIMUM_IDENTIFIER = 128;

  public String requireObjectKey() {
    if (objectKey == null || objectKey.isBlank()) {
      throw new InvalidBatchSubmissionException("objectKey must not be blank");
    }
    if (objectKey.length() > MAXIMUM_OBJECT_KEY) {
      throw new InvalidBatchSubmissionException(
          "objectKey must be at most " + MAXIMUM_OBJECT_KEY + " characters");
    }
    return objectKey.trim();
  }

  public String requireChecksum() {
    String normalized =
        checksumSha256 == null ? "" : checksumSha256.trim().toLowerCase(Locale.ROOT);
    if (!SHA256_HEX.matcher(normalized).matches()) {
      throw new InvalidBatchSubmissionException(
          "checksumSha256 must be a 64-character hexadecimal SHA-256 digest");
    }
    return normalized;
  }

  public BatchDeclaration requireDeclaration() {
    return new BatchDeclaration(
        requireIdentifier(planId, "planId"),
        requireIdentifier(payrollPeriod, "payrollPeriod"),
        requireValueDate(),
        requireNonNegative(declaredTotalCents, "declaredTotalCents"),
        requireRowCount(),
        requireSourceCents());
  }

  private LocalDate requireValueDate() {
    if (valueDate == null || valueDate.isBlank()) {
      throw new InvalidBatchSubmissionException("valueDate must not be blank");
    }
    try {
      return LocalDate.parse(valueDate.trim());
    } catch (DateTimeParseException e) {
      throw new InvalidBatchSubmissionException("valueDate must be a YYYY-MM-DD date");
    }
  }

  private int requireRowCount() {
    if (declaredRowCount == null) {
      throw new InvalidBatchSubmissionException("declaredRowCount must be present");
    }
    if (declaredRowCount <= 0) {
      // A batch with no rows is not a batch. Zero here would reach the validator as a
      // file that must be empty, which the parser refuses anyway — refusing it at the
      // edge gives the employer a 400 that says which field is wrong.
      throw new InvalidBatchSubmissionException("declaredRowCount must be greater than zero");
    }
    return declaredRowCount;
  }

  private Map<ContributionSource, Long> requireSourceCents() {
    Map<ContributionSource, Long> declared = new EnumMap<>(ContributionSource.class);
    if (declaredSourceCents != null) {
      for (Map.Entry<String, Long> entry : declaredSourceCents.entrySet()) {
        declared.put(requireSource(entry.getKey()), requireNonNegative(entry.getValue(), "amount"));
      }
    }
    return declared;
  }

  private static ContributionSource requireSource(String raw) {
    try {
      return ContributionSource.valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      // The offending value is not echoed: it is caller-supplied text, and a problem
      // detail reaches logs everywhere it passes through.
      throw new InvalidBatchSubmissionException(
          "declaredSourceCents is keyed by contribution source, and one key is not a known one");
    }
  }

  private static long requireNonNegative(Long value, String field) {
    if (value == null) {
      throw new InvalidBatchSubmissionException(field + " must be present");
    }
    if (value < 0) {
      throw new InvalidBatchSubmissionException(field + " must not be negative");
    }
    return value;
  }

  private static String requireIdentifier(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new InvalidBatchSubmissionException(field + " must not be blank");
    }
    if (value.length() > MAXIMUM_IDENTIFIER) {
      throw new InvalidBatchSubmissionException(
          field + " must be at most " + MAXIMUM_IDENTIFIER + " characters");
    }
    return value.trim();
  }
}

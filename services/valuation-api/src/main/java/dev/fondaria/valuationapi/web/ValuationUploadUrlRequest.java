package dev.fondaria.valuationapi.web;

import dev.fondaria.valuationapi.ingestion.InvalidValuationSubmissionException;

/**
 * Upload-slot request: who is delivering, and what they call the file.
 *
 * <p>The file name is carried through for audit and never becomes the object key — it is
 * caller-controlled text, and a key built from it could name any object in the bucket.
 */
public record ValuationUploadUrlRequest(String source, String fileName) {

  private static final int MAXIMUM_IDENTIFIER = 128;
  private static final int MAXIMUM_FILE_NAME = 255;

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

  public String requireFileName() {
    if (fileName == null || fileName.isBlank()) {
      throw new InvalidValuationSubmissionException("fileName must not be blank");
    }
    if (fileName.length() > MAXIMUM_FILE_NAME) {
      throw new InvalidValuationSubmissionException(
          "fileName must be at most " + MAXIMUM_FILE_NAME + " characters");
    }
    return fileName.trim();
  }
}

package dev.fondaria.valuationapi.objectstore;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/**
 * A presigned upload slot: where to {@code PUT} the file's bytes, and when that stops working.
 *
 * @param url the presigned URL. Carries the signature as a query parameter, so it is a credential —
 *     it is returned to the caller and never logged (docs/ENGINEERING-RULES.md §7).
 */
public record PresignedUpload(String objectKey, URI url, Instant expiresAt) {

  public PresignedUpload {
    Objects.requireNonNull(objectKey, "objectKey must not be null");
    Objects.requireNonNull(url, "url must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
  }

  /** No URL: it is a signed credential, and a record's default {@code toString} would leak it. */
  @Override
  public String toString() {
    return "PresignedUpload[objectKey="
        + objectKey
        + ", expiresAt="
        + expiresAt
        + ", url=REDACTED]";
  }
}

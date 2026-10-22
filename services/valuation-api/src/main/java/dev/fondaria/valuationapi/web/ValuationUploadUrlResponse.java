package dev.fondaria.valuationapi.web;

import dev.fondaria.valuationapi.objectstore.PresignedUpload;
import java.time.Instant;

/**
 * Where to {@code PUT} the file's bytes, and when that stops working.
 *
 * <p>{@code uploadUrl} carries its signature in the query string, so it is a credential. It is
 * returned to the caller and never logged (AGENTS.md §7).
 */
public record ValuationUploadUrlResponse(String objectKey, String uploadUrl, Instant expiresAt) {

  public static ValuationUploadUrlResponse of(PresignedUpload upload) {
    return new ValuationUploadUrlResponse(
        upload.objectKey(), upload.url().toString(), upload.expiresAt());
  }
}

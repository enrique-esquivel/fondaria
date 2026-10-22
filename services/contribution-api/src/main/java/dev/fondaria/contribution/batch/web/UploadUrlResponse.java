package dev.fondaria.contribution.batch.web;

import dev.fondaria.contribution.objectstore.PresignedUpload;

/**
 * The presigned slot, as the OpenAPI contract declares it.
 *
 * @param uploadUrl a signed credential with a short life. Returned to the caller and never logged.
 */
public record UploadUrlResponse(
    String objectKey, String uploadUrl, String uploadMethod, String expiresAt) {

  public static UploadUrlResponse of(PresignedUpload upload) {
    return new UploadUrlResponse(
        upload.objectKey(), upload.url().toString(), "PUT", upload.expiresAt().toString());
  }
}

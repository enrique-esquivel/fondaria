package dev.fondaria.contribution.batch;

import dev.fondaria.contribution.objectstore.ObjectStore;
import dev.fondaria.contribution.objectstore.ObjectStoreUnavailableException;
import dev.fondaria.contribution.objectstore.ObjectTooLargeException;
import dev.fondaria.contribution.objectstore.PresignedUpload;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * An {@link ObjectStore} holding whatever a test puts in it, with switches for the two failure
 * modes the runner has to tell apart: an object that is not there (the employer's problem, a
 * rejection) and a store that cannot be reached (ours, a retry). Collapsing those two is the bug
 * this double exists to catch.
 */
final class StubObjectStore implements ObjectStore {

  private final Map<String, byte[]> objects = new LinkedHashMap<>();
  private boolean unavailable;
  private long tooLargeAbove = Long.MAX_VALUE;

  void put(String objectKey, String content) {
    objects.put(objectKey, content.getBytes(StandardCharsets.UTF_8));
  }

  void putBytes(String objectKey, byte[] content) {
    objects.put(objectKey, content);
  }

  /** Models the store being unreachable — never "the object is missing". */
  void breakIt() {
    unavailable = true;
  }

  void fixIt() {
    unavailable = false;
  }

  void rejectAbove(long bytes) {
    tooLargeAbove = bytes;
  }

  @Override
  public PresignedUpload presignUpload(String objectKey) {
    if (unavailable) {
      throw new ObjectStoreUnavailableException(
          "stub store is unavailable", new IllegalStateException());
    }
    return new PresignedUpload(
        objectKey, URI.create("https://object-store.invalid/" + objectKey), Instant.EPOCH);
  }

  @Override
  public Optional<byte[]> fetch(String objectKey) {
    if (unavailable) {
      throw new ObjectStoreUnavailableException(
          "stub store is unavailable", new IllegalStateException());
    }
    byte[] content = objects.get(objectKey);
    if (content == null) {
      return Optional.empty();
    }
    if (content.length > tooLargeAbove) {
      throw new ObjectTooLargeException(content.length, tooLargeAbove);
    }
    return Optional.of(content);
  }
}

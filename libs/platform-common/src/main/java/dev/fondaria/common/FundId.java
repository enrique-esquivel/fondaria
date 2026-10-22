package dev.fondaria.common;

import java.util.Objects;
import java.util.UUID;

/**
 * The identifier of a fund — the pooled vehicle a participant's units are denominated in.
 *
 * <p>Unlike {@link OrganizationId}, a fund has no external natural key to derive an id from: it is
 * not registered by an outside party, it is created by an internal decision (Task 20.1). A fresh
 * random id is therefore assigned once, at registration, and never reassigned — matching {@code
 * reference.fund}'s unit ledger id, which is fixed for the same reason.
 */
public record FundId(UUID value) {

  public FundId {
    Objects.requireNonNull(value, "value must not be null");
  }

  public static FundId of(UUID value) {
    return new FundId(value);
  }

  public static FundId parse(String value) {
    Objects.requireNonNull(value, "value must not be null");
    try {
      return new FundId(UUID.fromString(value));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("fundId is not a valid UUID");
    }
  }

  @Override
  public String toString() {
    return value.toString();
  }
}

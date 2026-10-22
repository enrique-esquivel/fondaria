package dev.fondaria.ledger;

import java.util.Objects;

/**
 * Identifies one physical TigerBeetle cluster a ledger id (the cash ledger or one fund's unit
 * ledger) is deployed to (ADR-0005). Opaque configuration value, never a vendor cluster handle.
 */
public record ClusterId(String value) {

  public ClusterId {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("value must not be blank");
    }
  }

  public static ClusterId of(String value) {
    return new ClusterId(value);
  }
}

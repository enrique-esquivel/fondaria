package dev.fondaria.ledger;

/**
 * The {@code eventType} field of a ledger event, as {@code services/amqp-redpanda-bridge} forwards
 * it from the pinned {@code tigerbeetle amqp} 0.17.4 change-event payload (verified against {@code
 * src/tigerbeetle.zig}'s {@code ChangeEventType} — {@code @tagName} of the enum). The wire form is
 * lower snake_case.
 *
 * <p>An unrecognised value is a {@link MalformedLedgerEventException} — a change event whose
 * meaning the decoder does not understand must reach a human via the consumer's dead-letter topic,
 * never a best-effort guess. This mirrors the enum the bridge carries in {@code
 * dev.fondaria.bridge.cdc}; the two are deliberately not shared, because the bridge carries no
 * {@code libs/} domain types.
 */
public enum ChangeEventType {
  SINGLE_PHASE("single_phase"),
  TWO_PHASE_PENDING("two_phase_pending"),
  TWO_PHASE_POSTED("two_phase_posted"),
  TWO_PHASE_VOIDED("two_phase_voided"),
  TWO_PHASE_EXPIRED("two_phase_expired");

  private final String wireName;

  ChangeEventType(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  public static ChangeEventType fromWire(String value) {
    for (ChangeEventType type : values()) {
      if (type.wireName.equals(value)) {
        return type;
      }
    }
    throw new MalformedLedgerEventException("unrecognised eventType: " + value);
  }
}

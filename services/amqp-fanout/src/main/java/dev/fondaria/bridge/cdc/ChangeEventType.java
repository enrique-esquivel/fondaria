package dev.fondaria.bridge.cdc;

/**
 * The {@code type} field of a TigerBeetle change event, as the pinned 0.17.4 {@code tigerbeetle
 * amqp} connector emits it (verified against {@code src/tigerbeetle.zig}'s {@code ChangeEventType}
 * and {@code src/cdc/runner.zig}'s JSON serialisation — {@code @tagName} of the enum). The wire
 * form is lower snake_case.
 *
 * <p>An unrecognised value is a {@link CdcMessageException}, never a best-effort guess — a change
 * event whose meaning the bridge does not understand must reach a human via the DLQ.
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
    throw new CdcMessageException("unrecognised change-event type: " + value);
  }
}

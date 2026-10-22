package dev.fondaria.ledger;

/** Which leg of a transfer a {@link LedgerEntryEvent} represents. */
public enum EntryDirection {
  DEBIT,
  CREDIT;

  public static EntryDirection fromWire(String value) {
    for (EntryDirection direction : values()) {
      if (direction.name().equals(value)) {
        return direction;
      }
    }
    throw new MalformedLedgerEventException("unrecognised direction: " + value);
  }
}

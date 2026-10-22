package dev.fondaria.ledger;

import java.util.Optional;

/**
 * A ledger event message whose {@code schemaVersion} this decoder does not recognise (it
 * understands {@code "v1"}), or which carries no {@code schemaVersion} at all.
 *
 * <p>The decoder raises this <b>before</b> reading any other field: an unknown version is never
 * best-effort parsed (phase-04-cdc.md Task 4.4). The consumer routes the message to its dead-letter
 * topic with this exception's message as the reason.
 */
public final class UnknownLedgerEventVersionException extends RuntimeException {

  private final String version;

  /**
   * @param version the unrecognised value, or {@code null} when the message carried no {@code
   *     schemaVersion}
   */
  public UnknownLedgerEventVersionException(String version) {
    super(
        "unrecognised ledger event schemaVersion: "
            + (version == null ? "(absent)" : "\"" + version + "\"")
            + " — this decoder understands \"v1\"");
    this.version = version;
  }

  /**
   * The unrecognised version string, or empty when the message carried no {@code schemaVersion}.
   */
  public Optional<String> version() {
    return Optional.ofNullable(version);
  }
}

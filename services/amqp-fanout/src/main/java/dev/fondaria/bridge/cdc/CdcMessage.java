package dev.fondaria.bridge.cdc;

/**
 * A parsed TigerBeetle change event — the JSON body {@code tigerbeetle amqp} 0.17.4 publishes to
 * the {@code tigerbeetle} exchange. Shape verified against the pinned source ({@code
 * src/cdc/runner.zig}, {@code pub const Message}); Task 4.4 will pin it as a committed schema with
 * a captured fixture.
 *
 * <p><b>Every 128-bit and 64-bit field is carried as its canonical decimal string.</b> The
 * connector serialises them as a JSON number when they fit a double exactly and as a JSON string
 * otherwise ({@code emit_nonportable_numbers_as_strings}); {@link CdcMessageParser} normalises both
 * forms to a string. The bridge is a transport — it partitions on these values and forwards them,
 * it never does arithmetic on them — so a string is the honest representation and sidesteps the
 * {@code long} overflow a u128 id would cause. Domain decoding to {@code Money}/{@code Units} by
 * ledger id is Task 4.4's job, above this service.
 *
 * @param timestamp the ledger commit timestamp of the event (u64 nanoseconds)
 * @param type single-phase or one of the two-phase lifecycle events
 * @param ledger the TigerBeetle ledger id (u32 — cash is 484, units are per-fund)
 */
public record CdcMessage(
    String timestamp,
    ChangeEventType type,
    long ledger,
    Transfer transfer,
    Account debitAccount,
    Account creditAccount) {

  /**
   * The transfer that moved. {@code amount} is guaranteed integral by the parser — a payload with a
   * fractional amount is rejected, never coerced.
   */
  public record Transfer(
      String id,
      String amount,
      String pendingId,
      String userData128,
      String userData64,
      long userData32,
      long timeout,
      int code,
      int flags,
      String timestamp) {}

  /** One side's account, with its post-event balance counters as the connector reported them. */
  public record Account(
      String id,
      String debitsPending,
      String debitsPosted,
      String creditsPending,
      String creditsPosted,
      String userData128,
      String userData64,
      long userData32,
      int code,
      int flags,
      String timestamp) {}
}

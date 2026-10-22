package dev.fondaria.balanceapi.asof;

import java.io.Serial;

/**
 * The participant exists, but no holding of theirs has any recorded change at or before the date
 * asked about — the date is before their first contribution to every fund they have ever held.
 *
 * <p><b>A {@code 404}, and never a zero balance.</b> The two are different claims, and the wrong
 * one is a lie about somebody's money: zero says "we have your record and you held nothing", while
 * the truth is "our records do not reach that far back". A participant told they had nothing on a
 * date they had not yet joined would reasonably conclude a contribution went missing.
 *
 * <p>Deliberately not the same exception as "no such participant": that one is answered before this
 * check runs, from {@code master.participant}, and conflating them would make an unrecognised id
 * indistinguishable from a date out of range.
 *
 * <p>Carries no value — neither the id nor the date reaches a problem detail or a log
 * (docs/ENGINEERING-RULES.md §7).
 */
public final class NoHoldingOnRecordException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public NoHoldingOnRecordException() {
    super("no participant_units row exists at or before the requested date");
  }
}

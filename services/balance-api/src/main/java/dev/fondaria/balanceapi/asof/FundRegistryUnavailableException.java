package dev.fondaria.balanceapi.asof;

import java.io.Serial;

/**
 * {@code reference.fund_identity} came back empty, so no holding account can be derived and no
 * question can be asked.
 *
 * <p><b>A {@code 503}, not the {@code 404} that says the records do not reach that far back.</b> An
 * empty registry is a claim about the platform — the wrong database, a restore that has not
 * replayed {@code reference} yet, a fund-less deployment — and never a claim about a participant's
 * history. Answering it as {@link NoHoldingOnRecordException} would tell every participant, for
 * every date, that we have no record of them, which is the precise falsehood that exception exists
 * to prevent.
 *
 * <p>Task 6.2 answers the mirror-image case the same way: {@code PricesUnavailableException} is a
 * {@code 503} rather than a zero balance, because "this instance cannot value anything" is not "the
 * participant holds nothing". The movement history returning an empty page for an empty registry is
 * a different trade — an empty history is a weaker claim than an empty balance — and is left alone.
 */
public final class FundRegistryUnavailableException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public FundRegistryUnavailableException() {
    super("no fund is registered, so no historical holding can be resolved");
  }
}

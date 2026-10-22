package dev.fondaria.balanceapi.movement;

import dev.fondaria.common.FundId;
import dev.fondaria.common.Units;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One unit-ledger entry on one of a participant's own holding accounts — {@code Movement} in {@code
 * contracts/openapi/balance-api.yaml} (Task 6.3).
 *
 * <p><b>A leg, not a transfer.</b> A contribution allotment credits the participant's holding and
 * debits {@code UNITS_IN_ISSUE}, which is nobody's account, so it is one movement. A {@code
 * VESTING} transfer moves units between the participant's own two accounts and is therefore two — a
 * debit on unvested and a credit on vested, both real, and reporting only one of them would make
 * the history stop reconciling with the holding.
 *
 * <p><b>No cash movement ever appears here.</b> A participant holds no cash account of their own;
 * the employer's money sits in pooled cash accounts until an allotment turns it into units. The
 * employer-facing view of money in flight is the contribution batch's own status resource (Task
 * 21.1), the same boundary Task 6.2 drew for the balance.
 *
 * <p><b>No value.</b> A movement is a quantity of units at a point in the ledger, not pesos: what
 * those units were worth then is {@code units × NAV} at that day's price, and the statement (Task
 * 7.5) is where that waterfall belongs. Multiplying here with today's price would be a number that
 * looks like history and is not.
 *
 * @param transferId canonical unsigned-decimal u128 — text, because it exceeds a JSON number's safe
 *     integer range, the same convention the CDC decoder uses
 * @param ledgerTimestamp TigerBeetle's u64 nanosecond commit timestamp, unsigned decimal text for
 *     the same reason. Part of the keyset ordering key
 * @param navVersionId which {@code reference.fund_nav} row priced this movement, when one did.
 *     Empty for a movement no NAV strikes — {@code VESTING} moves units already priced.
 *     <b>Reserved: empty on every row today.</b> The CDC change event carries no NAV version, so
 *     {@code JdbcProjectionSink} (Task 5.4) leaves {@code projection.transfer.nav_version_id} unset
 *     on every row it writes. This field reads a column nothing populates yet; it is carried
 *     through faithfully when a row does have one, which is what the integration test exercises by
 *     seeding the column directly. The authoritative record of which NAV priced an allotment is
 *     {@code workflow.allotment.nav_version_id} (Task 21.2)
 */
public record Movement(
    String transferId,
    FundId fundId,
    HoldingAccount holdingAccount,
    MovementDirection direction,
    Units units,
    MovementTransferCode transferCode,
    Optional<Long> navVersionId,
    LocalDate bookingDate,
    LocalDate valueDate,
    String ledgerTimestamp) {

  public Movement {
    Objects.requireNonNull(transferId, "transferId must not be null");
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(holdingAccount, "holdingAccount must not be null");
    Objects.requireNonNull(direction, "direction must not be null");
    Objects.requireNonNull(units, "units must not be null");
    Objects.requireNonNull(transferCode, "transferCode must not be null");
    Objects.requireNonNull(navVersionId, "navVersionId must not be null");
    Objects.requireNonNull(bookingDate, "bookingDate must not be null");
    Objects.requireNonNull(valueDate, "valueDate must not be null");
    Objects.requireNonNull(ledgerTimestamp, "ledgerTimestamp must not be null");
  }
}

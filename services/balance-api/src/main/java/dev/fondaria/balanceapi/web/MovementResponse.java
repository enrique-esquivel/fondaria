package dev.fondaria.balanceapi.web;

import dev.fondaria.balanceapi.movement.Movement;
import java.time.LocalDate;

/**
 * One movement — {@code Movement} in {@code contracts/openapi/balance-api.yaml}.
 *
 * <p>{@code unitsScaled} is a plain JSON integer at scale 1e-8, a positive magnitude; the direction
 * is its own field and never the sign (AGENTS.md §4).
 *
 * <p><b>{@code transferId} and {@code ledgerTimestamp} are strings, and that is not an amount
 * rendered as text.</b> They are a u128 and a u64: both exceed a JSON number's safe integer range,
 * so a client parsing them as numbers would silently round them, and an id that rounds is an id
 * that stops matching the ledger. The convention is the CDC decoder's own.
 *
 * @param navVersionId which {@code reference.fund_nav} row priced this movement, or {@code null}
 *     where no NAV struck it — a vesting movement moves units that were priced when they were
 *     issued
 */
public record MovementResponse(
    String transferId,
    String fundId,
    String holdingAccount,
    String direction,
    long unitsScaled,
    String transferCode,
    Long navVersionId,
    LocalDate bookingDate,
    LocalDate valueDate,
    String ledgerTimestamp) {

  public static MovementResponse from(Movement movement) {
    return new MovementResponse(
        movement.transferId(),
        movement.fundId().toString(),
        movement.holdingAccount().name(),
        movement.direction().name(),
        movement.units().scaled(),
        movement.transferCode().name(),
        movement.navVersionId().orElse(null),
        movement.bookingDate(),
        movement.valueDate(),
        movement.ledgerTimestamp());
  }
}

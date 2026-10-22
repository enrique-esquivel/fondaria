package dev.fondaria.balanceapi.web;

import dev.fondaria.balanceapi.movement.InvalidMovementQueryException;
import dev.fondaria.balanceapi.movement.MovementQuery;
import dev.fondaria.balanceapi.movement.MovementService;
import dev.fondaria.balanceapi.movement.MovementTransferCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.ParticipantId;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/participants/{participantId}/movements} — Task 6.3's endpoint, as specified in
 * {@code contracts/openapi/balance-api.yaml}.
 *
 * <p>Thin on purpose: parse, delegate, map. Everything worth testing about a page is in {@code
 * MovementService}, and the failure translations are in {@link MovementExceptionHandler}.
 *
 * <p><b>Every parameter arrives as text and is parsed here.</b> Binding {@code LocalDate} or an
 * {@code enum} directly would let Spring produce the failure instead, and its message quotes the
 * offending value — which on this route is a caller-supplied string in a URL that a problem detail
 * must not echo back (docs/ENGINEERING-RULES.md §7). Parsing by hand is what keeps the {@code 400}
 * body free of the caller's own input.
 *
 * <p><b>Unauthenticated in v1</b>, like every other endpoint here, and it must not be exposed
 * outside the cluster until Phase 9/10 lands. The cursor's signature is a tamper check, never an
 * authorization check.
 *
 * <p><b>The {@code @PathVariable} and every {@code @RequestParam} are named explicitly</b>: the
 * build does not compile with {@code -parameters}, so an unnamed one resolves to nothing at runtime
 * and every request fails.
 */
@RestController
public class MovementController {

  private final MovementService movements;

  public MovementController(MovementService movements) {
    this.movements = movements;
  }

  @GetMapping("/v1/participants/{participantId}/movements")
  public MovementPageResponse listParticipantMovements(
      @PathVariable("participantId") String participantId,
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to,
      @RequestParam(name = "fundId", required = false) String fundId,
      @RequestParam(name = "transferCode", required = false) String transferCode,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit) {
    ParticipantId pId = participant(participantId);
    MovementQuery query =
        new MovementQuery(
            pId,
            date(from, "from"),
            date(to, "to"),
            fund(fundId),
            code(transferCode),
            limit(limit),
            Optional.ofNullable(cursor));
    return MovementPageResponse.from(movements.movements(query));
  }

  private static ParticipantId participant(String participantId) {
    try {
      return ParticipantId.parse(participantId);
    } catch (IllegalArgumentException e) {
      throw new MalformedParticipantIdException(e);
    }
  }

  private static Optional<LocalDate> date(String value, String parameter) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(LocalDate.parse(value));
    } catch (DateTimeParseException e) {
      throw new InvalidMovementQueryException(parameter + " must be a date, as YYYY-MM-DD.");
    }
  }

  private static Optional<FundId> fund(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(new FundId(UUID.fromString(value)));
    } catch (IllegalArgumentException e) {
      throw new InvalidMovementQueryException("fundId must be a UUID.");
    }
  }

  private static Optional<MovementTransferCode> code(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(
        MovementTransferCode.parse(value)
            .orElseThrow(
                () ->
                    new InvalidMovementQueryException(
                        "transferCode must be one of: " + supportedCodes() + ".")));
  }

  private static int limit(String value) {
    if (value == null || value.isBlank()) {
      return MovementQuery.DEFAULT_LIMIT;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new InvalidMovementQueryException(
          "limit must be a whole number between 1 and " + MovementQuery.MAXIMUM_LIMIT + ".");
    }
  }

  /** Our own enum names, never the caller's text. */
  private static String supportedCodes() {
    StringBuilder codes = new StringBuilder();
    for (MovementTransferCode code : MovementTransferCode.values()) {
      if (!codes.isEmpty()) {
        codes.append(", ");
      }
      codes.append(code.name());
    }
    return codes.toString();
  }
}

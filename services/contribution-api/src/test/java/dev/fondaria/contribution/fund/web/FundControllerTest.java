package dev.fondaria.contribution.fund.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.FundId;
import dev.fondaria.common.Units;
import dev.fondaria.contribution.fund.InMemoryFundRepository;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Task D.0's fund-registration acceptance criteria, at the level where the decisions are made. */
class FundControllerTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);

  @Test
  @DisplayName("registering a fund assigns a unit ledger id in the reserved range and returns 201")
  void registrationAssignsAUnitLedgerId() {
    FundController controller = new FundController(new InMemoryFundRepository(), CLOCK);

    ResponseEntity<FundResponse> response = controller.register(request(null, "2026-01-01", 1));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody().unitLedgerId()).isBetween(1_001L, 2_000_000_000L);
    assertThat(response.getBody().status()).isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("replaying the identical registration is a no-op 200, not a second fund")
  void replayIsANoOp() {
    FundController controller = new FundController(new InMemoryFundRepository(), CLOCK);
    String fundId = UUID.randomUUID().toString();

    ResponseEntity<FundResponse> first = controller.register(request(fundId, "2026-01-01", 1));
    ResponseEntity<FundResponse> second = controller.register(request(fundId, "2026-01-01", 1));

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getBody().unitLedgerId()).isEqualTo(first.getBody().unitLedgerId());
    assertThat(second.getBody().publishedAt()).isEqualTo(first.getBody().publishedAt());
  }

  @Test
  @DisplayName("the same (fundId, version) with different content is a conflict, not a replay")
  void conflictingReplayIsRejected() {
    FundController controller = new FundController(new InMemoryFundRepository(), CLOCK);
    String fundId = UUID.randomUUID().toString();
    controller.register(request(fundId, "2026-01-01", 1));

    RegisterFundRequest differentName =
        new RegisterFundRequest(
            fundId,
            "2026-01-01",
            1,
            "A different name",
            "CAJA_DE_AHORRO",
            "MXN",
            Units.SCALE,
            "2026-01-01",
            10 * Units.SCALE);

    assertThatThrownBy(() -> controller.register(differentName))
        .isInstanceOf(FundVersionConflictException.class);
  }

  @Test
  @DisplayName("a second version of an existing fund reuses the ledger id already on record")
  void secondVersionReusesTheLedgerId() {
    FundController controller = new FundController(new InMemoryFundRepository(), CLOCK);
    String fundId = UUID.randomUUID().toString();
    ResponseEntity<FundResponse> firstVersion =
        controller.register(request(fundId, "2026-01-01", 1));

    ResponseEntity<FundResponse> secondVersion =
        controller.register(request(fundId, "2027-01-01", 2));

    assertThat(secondVersion.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(secondVersion.getBody().unitLedgerId())
        .isEqualTo(firstVersion.getBody().unitLedgerId());
  }

  @Test
  @DisplayName(
      "a second version disagreeing with the fund's fixed identity is rejected, not echoed")
  void secondVersionWithDifferentIdentityIsRejected() {
    FundController controller = new FundController(new InMemoryFundRepository(), CLOCK);
    String fundId = UUID.randomUUID().toString();
    controller.register(request(fundId, "2026-01-01", 1));

    RegisterFundRequest differentLaunchNav =
        new RegisterFundRequest(
            fundId,
            "2027-01-01",
            2,
            "Caja de Ahorro Acme",
            "CAJA_DE_AHORRO",
            "MXN",
            Units.SCALE,
            "2026-01-01",
            // A corrected opening NAV a second version cannot actually apply: the identity
            // insert is ON CONFLICT DO NOTHING, so silently accepting this would echo a price
            // never persisted.
            20 * Units.SCALE);

    assertThatThrownBy(() -> controller.register(differentLaunchNav))
        .isInstanceOf(InvalidFundRequestException.class);
  }

  @Test
  @DisplayName(
      "a colliding unit ledger id is retried with a different one, not surfaced to the caller")
  void ledgerIdCollisionIsRetried() {
    FlakyOnceFundRepository repository = new FlakyOnceFundRepository();
    FundController controller = new FundController(repository, CLOCK);

    ResponseEntity<FundResponse> response = controller.register(request(null, "2026-01-01", 1));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(repository.attemptedLedgerIds()).hasSize(2);
    assertThat(Set.copyOf(repository.attemptedLedgerIds()))
        .as("the retry must not resubmit the same ledger id that just collided")
        .hasSize(2);
    assertThat(response.getBody().unitLedgerId()).isEqualTo(repository.attemptedLedgerIds().get(1));
  }

  @Test
  @DisplayName("a conflict unrelated to the ledger id is not misreported as an allocation failure")
  void unrelatedConstraintViolationIsNotRetried() {
    FundController controller = new FundController(new AlwaysConflictingFundRepository(), CLOCK);

    assertThatThrownBy(() -> controller.register(request(null, "2026-01-01", 1)))
        .isInstanceOf(FundVersionConflictException.class);
  }

  @Test
  @DisplayName("a blank name is rejected before anything is published")
  void blankNameIsRejected() {
    FundController controller = new FundController(new InMemoryFundRepository(), CLOCK);
    RegisterFundRequest blankName =
        new RegisterFundRequest(
            null,
            "2026-01-01",
            1,
            "  ",
            "CAJA_DE_AHORRO",
            "MXN",
            Units.SCALE,
            "2026-01-01",
            10 * Units.SCALE);

    assertThatThrownBy(() -> controller.register(blankName))
        .isInstanceOf(InvalidFundRequestException.class);
  }

  private static RegisterFundRequest request(String fundId, String effectiveFrom, int version) {
    return new RegisterFundRequest(
        fundId,
        effectiveFrom,
        version,
        "Caja de Ahorro Acme",
        "CAJA_DE_AHORRO",
        "MXN",
        Units.SCALE,
        "2026-01-01",
        10 * Units.SCALE);
  }

  /**
   * Throws once, on the first attempted ledger id, with the real constraint's name — then
   * delegates. Proves the retry loop actually tries a different id, not just that it eventually
   * succeeds.
   */
  private static final class FlakyOnceFundRepository implements FundRepository {

    private final InMemoryFundRepository delegate = new InMemoryFundRepository();
    private final List<Long> attemptedLedgerIds = new ArrayList<>();
    private boolean thrown;

    List<Long> attemptedLedgerIds() {
      return attemptedLedgerIds;
    }

    @Override
    public Optional<Fund> resolveAsOf(FundId fundId, LocalDate asOfDate) {
      return delegate.resolveAsOf(fundId, asOfDate);
    }

    @Override
    public Fund publish(Fund candidate) {
      attemptedLedgerIds.add(candidate.unitLedgerId());
      if (!thrown) {
        thrown = true;
        throw new DataIntegrityViolationException(
            "duplicate key value violates unique constraint \"fund_identity_unit_ledger_id_unique\"");
      }
      return delegate.publish(candidate);
    }
  }

  /** Always fails with a constraint the ledger-id retry loop cannot fix by trying a new id. */
  private static final class AlwaysConflictingFundRepository implements FundRepository {

    @Override
    public Optional<Fund> resolveAsOf(FundId fundId, LocalDate asOfDate) {
      return Optional.empty();
    }

    @Override
    public Fund publish(Fund candidate) {
      throw new DataIntegrityViolationException(
          "duplicate key value violates unique constraint \"fund_pk\"");
    }
  }
}

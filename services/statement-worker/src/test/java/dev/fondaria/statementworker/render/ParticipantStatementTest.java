package dev.fondaria.statementworker.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.ParticipantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The waterfall's arithmetic and the determinism Task 7.5b's acceptance criteria rest on: the
 * balance identity holds, market movement is signed and unclamped, and a rerun produces an
 * identical hash.
 */
class ParticipantStatementTest {

  private static final ParticipantId PARTICIPANT_ID = ParticipantId.of(UUID.randomUUID());
  private static final Instant GENERATED_AT = Instant.parse("2026-08-31T23:00:00.123456Z");

  @Test
  @DisplayName("opening + contributions - withdrawals - fees - tax + market movement == closing")
  void balancesExactlyOnARisingMonth() {
    ParticipantStatement statement =
        ParticipantStatement.of(
            PARTICIPANT_ID, "2026-08", 45_100_00L, 90_000L, 90_000L, 0L, 47_382_15L, GENERATED_AT);

    long reconstructedClosing =
        statement.openingBalanceCents()
            + statement.contributionsEmployeeCents()
            + statement.contributionsEmployerCents()
            + statement.contributionsVoluntaryCents()
            - statement.withdrawalsCents()
            - statement.feesCents()
            - statement.taxWithheldCents()
            + statement.marketMovementCents();

    assertThat(reconstructedClosing).isEqualTo(statement.closingBalanceCents());
    assertThat(statement.closingBalanceCents()).isEqualTo(47_382_15L);
    assertThat(statement.marketMovementCents()).isPositive();
  }

  @Test
  @DisplayName("a down month produces a negative, unclamped market movement")
  void marketMovementIsNegativeInADownPeriod() {
    // Opening 100,000.00, contributions 1,000.00, closing only 90,500.00: the pool lost more than
    // the contributions gained, so the plug must be negative — never floored at zero.
    ParticipantStatement statement =
        ParticipantStatement.of(
            PARTICIPANT_ID, "2026-08", 100_000_00L, 100_000L, 0L, 0L, 90_500_00L, GENERATED_AT);

    assertThat(statement.marketMovementCents()).isEqualTo(90_500_00L - 100_000_00L - 100_000L);
    assertThat(statement.marketMovementCents()).isNegative();
  }

  @Test
  @DisplayName("employee and employer contributions are carried as separate fields")
  void employeeAndEmployerAreSeparate() {
    ParticipantStatement statement =
        ParticipantStatement.of(
            PARTICIPANT_ID, "2026-08", 0L, 1_000L, 2_000L, 3_000L, 6_000L, GENERATED_AT);

    assertThat(statement.contributionsEmployeeCents()).isEqualTo(1_000L);
    assertThat(statement.contributionsEmployerCents()).isEqualTo(2_000L);
    assertThat(statement.contributionsVoluntaryCents()).isEqualTo(3_000L);
  }

  @Test
  @DisplayName("withdrawals, fees and tax are zero in v1 but present as real fields")
  void withdrawalsFeesAndTaxAreZeroInV1() {
    ParticipantStatement statement =
        ParticipantStatement.of(PARTICIPANT_ID, "2026-08", 0L, 0L, 0L, 0L, 0L, GENERATED_AT);

    assertThat(statement.withdrawalsCents()).isZero();
    assertThat(statement.feesCents()).isZero();
    assertThat(statement.taxWithheldCents()).isZero();
  }

  @Test
  @DisplayName("rerun produces an identical hash")
  void rerunProducesAnIdenticalHash() {
    ParticipantStatement first =
        ParticipantStatement.of(
            PARTICIPANT_ID, "2026-08", 45_100_00L, 90_000L, 90_000L, 0L, 47_382_15L, GENERATED_AT);
    ParticipantStatement second =
        ParticipantStatement.of(
            PARTICIPANT_ID, "2026-08", 45_100_00L, 90_000L, 90_000L, 0L, 47_382_15L, GENERATED_AT);

    RenderedObject firstRendered = first.render();
    RenderedObject secondRendered = second.render();

    assertThat(secondRendered.sha256()).isEqualTo(firstRendered.sha256());
    assertThat(secondRendered.bytes()).isEqualTo(firstRendered.bytes());
  }

  @Test
  @DisplayName(
      "a different generatedAt renders different bytes — the value must come from the close, not the render's own clock")
  void generatedAtIsPartOfTheHashedContent() {
    ParticipantStatement first =
        ParticipantStatement.of(PARTICIPANT_ID, "2026-08", 0L, 0L, 0L, 0L, 0L, GENERATED_AT);
    ParticipantStatement second =
        ParticipantStatement.of(
            PARTICIPANT_ID, "2026-08", 0L, 0L, 0L, 0L, 0L, GENERATED_AT.plusSeconds(1));

    assertThat(second.render().sha256()).isNotEqualTo(first.render().sha256());
  }

  @Test
  @DisplayName("the footer names the rounding-policy document")
  void footerNamesTheRoundingPolicyDocument() {
    ParticipantStatement statement =
        ParticipantStatement.of(PARTICIPANT_ID, "2026-08", 0L, 0L, 0L, 0L, 0L, GENERATED_AT);
    assertThat(statement.render().bytes()).asString().contains("docs/rounding-policy.md");
  }
}

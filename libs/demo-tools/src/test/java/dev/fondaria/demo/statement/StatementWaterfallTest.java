package dev.fondaria.demo.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StatementWaterfallTest {

  /** Field for field what statement-worker's {@code ParticipantStatement#render} writes. */
  private static String statement(
      long opening, long employee, long employer, long market, long closing) {
    return """
        {"participantId":"44444444-4444-4444-4444-444444444444","period":"2026-10",\
        "openingBalanceCents":%d,"contributionsEmployeeCents":%d,\
        "contributionsEmployerCents":%d,"contributionsVoluntaryCents":0,"withdrawalsCents":0,\
        "feesCents":0,"taxWithheldCents":0,"marketMovementCents":%d,"closingBalanceCents":%d,\
        "generatedAt":"2026-10-30T23:05:31Z","footerDisclosure":"El remanente de redondeo"}
        """
        .formatted(opening, employee, employer, market, closing);
  }

  @Test
  @DisplayName("a rising month adds up to the closing balance")
  void risingMonth() {
    StatementWaterfall s = StatementWaterfall.parse(statement(0, 90_000, 90_000, 4_812, 184_812));

    assertThat(s.reconciles()).isTrue();
    assertThat(String.join("\n", s.render()))
        .contains("+ Employee contributions", "$900.00", "+ Market movement", "$48.12")
        .contains("Closing balance", "$1,848.12")
        .contains("to the centavo");
  }

  @Test
  @DisplayName("a falling NAV shows a negative market movement, unclamped, and still adds up")
  void fallingMonth() {
    StatementWaterfall s =
        StatementWaterfall.parse(statement(1_000_000, 90_000, 90_000, -228_212, 951_788));

    assertThat(s.reconciles()).isTrue();
    assertThat(String.join("\n", s.render())).contains("-$2,282.12").contains("$9,517.88");
  }

  @Test
  @DisplayName("lines that do not add up are called out, never papered over")
  void inconsistentStatementIsFlagged() {
    StatementWaterfall s = StatementWaterfall.parse(statement(0, 90_000, 90_000, 0, 180_001));

    assertThat(s.reconciles()).isFalse();
    assertThat(String.join("\n", s.render())).contains("DO NOT ADD UP");
  }

  @Test
  @DisplayName("the object key is statement-worker's, and only a UUID reaches it")
  void objectKey() {
    assertThat(
            StatementsCommand.objectKey(
                YearMonth.of(2026, 10), "44444444-4444-4444-4444-444444444444"))
        .isEqualTo(
            "statements/period=2026-10/participant=44444444-4444-4444-4444-444444444444.json");
    assertThatThrownBy(() -> StatementsCommand.objectKey(YearMonth.of(2026, 10), "x; rm -rf /"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void runStatusParses() {
    StatementRunStatus run =
        StatementRunStatus.fromRow(java.util.List.of("COMPLETED", "60000", "60000", "2026-10-30"));

    assertThat(run.completed()).isTrue();
    assertThat(run.failed()).isFalse();
    assertThat(run.expectedCount()).isEqualTo(60_000L);
  }
}

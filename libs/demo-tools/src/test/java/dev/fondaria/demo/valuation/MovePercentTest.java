package dev.fondaria.demo.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.demo.DemoToolsException;
import org.junit.jupiter.api.Test;

class MovePercentTest {

  @Test
  void parsesNegativePercentagesWithSuffix() {
    assertThat(MovePercent.parse("-0.8%")).isEqualTo(-800L);
    assertThat(MovePercent.parse("-1.5%")).isEqualTo(-1500L);
  }

  @Test
  void parsesPositivePercentagesWithAndWithoutSign() {
    assertThat(MovePercent.parse("0.3%")).isEqualTo(300L);
    assertThat(MovePercent.parse("+0.3%")).isEqualTo(300L);
  }

  @Test
  void parsesWithoutAPercentSuffix() {
    assertThat(MovePercent.parse("-0.8")).isEqualTo(-800L);
  }

  @Test
  void parsesWholeNumberPercentages() {
    assertThat(MovePercent.parse("2%")).isEqualTo(2000L);
    assertThat(MovePercent.parse("-2%")).isEqualTo(-2000L);
  }

  @Test
  void matchesEveryScriptedMove() {
    assertThat(MovePercent.parse("0.3%")).isEqualTo(300L);
    assertThat(MovePercent.parse("0.1%")).isEqualTo(100L);
    assertThat(MovePercent.parse("-0.8%")).isEqualTo(-800L);
    assertThat(MovePercent.parse("0.2%")).isEqualTo(200L);
    assertThat(MovePercent.parse("-1.5%")).isEqualTo(-1500L);
    assertThat(MovePercent.parse("0.4%")).isEqualTo(400L);
  }

  @Test
  void rejectsAMisplacedSignInsteadOfMisparsingIt() {
    // "1.-5" must never silently become the fraction -50 (and so 950 overall) -- reject it
    // outright.
    assertThatThrownBy(() -> MovePercent.parse("1.-5")).isInstanceOf(DemoToolsException.class);
  }

  @Test
  void rejectsNonNumericInput() {
    assertThatThrownBy(() -> MovePercent.parse("abc")).isInstanceOf(DemoToolsException.class);
    assertThatThrownBy(() -> MovePercent.parse("1.2.3")).isInstanceOf(DemoToolsException.class);
  }
}

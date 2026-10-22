package dev.fondaria.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ArgsTest {

  @Test
  void parsesFlagValuePairs() {
    Args args = Args.parse(new String[] {"seed", "--seed", "42", "--period", "2026-10-A"}, 1);
    assertThat(args.require("seed")).isEqualTo("42");
    assertThat(args.get("period", "default")).isEqualTo("2026-10-A");
  }

  @Test
  void bareSwitchesAreDetectedByHas() {
    Args args = Args.parse(new String[] {"contributions", "--corrupt"}, 1);
    assertThat(args.has("corrupt")).isTrue();
    assertThat(args.has("period")).isFalse();
  }

  @Test
  void missingRequiredFlagThrows() {
    Args args = Args.parse(new String[] {"seed"}, 1);
    assertThatThrownBy(() -> args.require("seed")).isInstanceOf(DemoToolsException.class);
  }

  @Test
  void numericAccessorsParseTheirValues() {
    Args args =
        Args.parse(new String[] {"onboard", "--employees", "200000", "--business-days", "3"}, 1);
    assertThat(args.getInt("employees", -1)).isEqualTo(200_000);
    assertThat(args.getLong("business-days", -1L)).isEqualTo(3L);
  }

  @Test
  void defaultsAreUsedWhenAFlagIsAbsent() {
    Args args = Args.parse(new String[] {"seed"}, 1);
    assertThat(args.getLong("seed", 42L)).isEqualTo(42L);
    assertThat(args.getInt("employees", 200_000)).isEqualTo(200_000);
  }
}

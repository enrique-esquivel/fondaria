package dev.fondaria.demo.k8s;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PsqlRowsTest {

  private static final String SEP = PsqlRows.FIELD_SEPARATOR;

  @Test
  void splitsRowsAndColumnsAndKeepsANullAsEmpty() {
    String output = "PASSED" + SEP + "0" + SEP + "\n" + "FAILED" + SEP + "3" + SEP + "x\n\n";

    assertThat(PsqlRows.parse(output))
        .containsExactly(List.of("PASSED", "0", ""), List.of("FAILED", "3", "x"));
  }

  @Test
  void noRowsIsEmpty() {
    assertThat(PsqlRows.parse("")).isEmpty();
    assertThat(PsqlRows.single(PsqlRows.parse("\n"))).isEmpty();
  }

  @Test
  void singleRefusesMoreThanOneRow() {
    assertThatThrownBy(() -> PsqlRows.single(PsqlRows.parse("a\nb\n")))
        .isInstanceOf(IllegalStateException.class);
  }
}

package dev.fondaria.demo.close;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CloseProgressTest {

  private static String describe(
      String recon, String snapshot, String signedOff, String announced) {
    return CloseProgress.fromRow(List.of(recon, snapshot, signedOff, announced)).describe();
  }

  @Test
  void tellsTheCloseAsItHappens() {
    assertThat(describe("", "", "f", "f"))
        .contains("waiting for the first internal reconciliation");
    assertThat(describe("FAILED 4", "", "f", "f")).contains("found 4 break(s)");
    assertThat(describe("PASSED 0", "", "f", "f")).contains("waiting for the dense snapshot");
    assertThat(describe("PASSED 0", "COMPLETED", "f", "f")).contains("waiting for sign-off");
    assertThat(describe("PASSED 0", "COMPLETED", "t", "f")).contains("publishing");
    assertThat(describe("PASSED 0", "COMPLETED", "t", "t")).contains("published");
  }

  @Test
  void equalProgressIsNotReprinted() {
    assertThat(CloseProgress.fromRow(List.of("PASSED 0", "", "f", "f")))
        .isEqualTo(CloseProgress.fromRow(List.of("PASSED 0", "", "f", "f")));
  }
}

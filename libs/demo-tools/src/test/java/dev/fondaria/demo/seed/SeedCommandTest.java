package dev.fondaria.demo.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.demo.Args;
import dev.fondaria.demo.DemoToolsException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SeedCommandTest {

  @Test
  void organizationCountDefaultsToAll() {
    assertThat(SeedCommand.organizationCount(Args.parse(new String[] {"seed"}, 1), 12))
        .isEqualTo(12);
  }

  @Test
  void organizationCountAcceptsASubset() {
    Args args = Args.parse(new String[] {"seed", "--organizations", "8"}, 1);
    assertThat(SeedCommand.organizationCount(args, 12)).isEqualTo(8);
  }

  @Test
  void organizationCountRejectsOutOfRange() {
    for (String value : new String[] {"0", "13", "-1"}) {
      Args args = Args.parse(new String[] {"seed", "--organizations", value}, 1);
      assertThatThrownBy(() -> SeedCommand.organizationCount(args, 12))
          .isInstanceOf(DemoToolsException.class);
    }
  }

  @Test
  void firstEightOrganizationsAreTheSmallestSixtyThousand() {
    int[] sizes = OrganizationSizes.generate();
    long firstEight = 0;
    for (int i = 0; i < 8; i++) {
      firstEight += sizes[i];
    }
    assertThat(firstEight).isEqualTo(60_000);
  }

  @Test
  void settleTimeoutGrowsWithTheBatchSoTheLargestSeedOrganizationIsNotCutOff() {
    assertThat(SeedCommand.settleTimeout(191)).isLessThan(Duration.ofMinutes(4));
    // 31,115 rows took over four minutes to stage on the small GKE profile.
    assertThat(SeedCommand.settleTimeout(31_115)).isGreaterThan(Duration.ofMinutes(8));
  }
}

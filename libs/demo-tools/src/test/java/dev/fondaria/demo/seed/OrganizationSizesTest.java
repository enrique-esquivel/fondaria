package dev.fondaria.demo.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OrganizationSizesTest {

  @Test
  void everySizeIsWithinTheConfiguredRange() {
    int[] sizes = OrganizationSizes.generate();
    assertThat(sizes).hasSize(OrganizationSizes.COUNT);
    for (int size : sizes) {
      assertThat(size).isGreaterThanOrEqualTo(OrganizationSizes.MIN_EMPLOYEES);
      assertThat(size).isLessThanOrEqualTo(OrganizationSizes.MAX_EMPLOYEES);
    }
  }

  @Test
  void sumsToExactlyTheTargetTotal() {
    int[] sizes = OrganizationSizes.generate();
    int sum = Arrays.stream(sizes).sum();
    assertThat(sum).isEqualTo(OrganizationSizes.TARGET_TOTAL);
  }

  @Test
  void isLogDistributedNotUniform() {
    int[] sizes = OrganizationSizes.generate();
    // The smallest and largest organizations should differ by a wide margin -- a portfolio
    // dominated by a few large clients, not twelve near-identical employers.
    assertThat(sizes[sizes.length - 1]).isGreaterThan(sizes[0] * 10);
  }

  @Test
  void isDeterministic() {
    assertThat(OrganizationSizes.generate()).isEqualTo(OrganizationSizes.generate());
  }

  @Test
  void handlesATightRangeWithoutDividingByZero() {
    int[] sizes = OrganizationSizes.generate(3, 100, 100, 300);
    assertThat(sizes).containsExactly(100, 100, 100);
  }
}

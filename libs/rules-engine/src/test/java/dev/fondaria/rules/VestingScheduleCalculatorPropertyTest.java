package dev.fondaria.rules;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class VestingScheduleCalculatorPropertyTest {

  private static final LocalDate SERVICE_START = LocalDate.of(2020, 1, 1);

  @Property
  void vested_basis_points_never_leaves_zero_to_ten_thousand(
      @ForAll("vestingFullYears") int vestingFullYears, @ForAll("elapsedDays") long elapsedDays) {
    PlanVersion plan = planWithVestingFullYears(vestingFullYears);
    LocalDate asOf = SERVICE_START.plusDays(elapsedDays);

    long vested = VestingScheduleCalculator.vestedBasisPoints(plan, SERVICE_START, asOf);

    assertThat(vested).isBetween(0L, 10_000L);
  }

  @Property
  void more_elapsed_service_never_vests_less(
      @ForAll("vestingFullYears") int vestingFullYears,
      @ForAll("elapsedDays") long earlierDays,
      @ForAll("elapsedDays") long laterDays) {
    PlanVersion plan = planWithVestingFullYears(vestingFullYears);
    long first = Math.min(earlierDays, laterDays);
    long second = Math.max(earlierDays, laterDays);

    long vestedEarlier =
        VestingScheduleCalculator.vestedBasisPoints(
            plan, SERVICE_START, SERVICE_START.plusDays(first));
    long vestedLater =
        VestingScheduleCalculator.vestedBasisPoints(
            plan, SERVICE_START, SERVICE_START.plusDays(second));

    assertThat(vestedLater).isGreaterThanOrEqualTo(vestedEarlier);
  }

  @Provide
  Arbitrary<Integer> vestingFullYears() {
    return Arbitraries.integers().between(0, 40);
  }

  @Provide
  Arbitrary<Long> elapsedDays() {
    return Arbitraries.longs().between(0L, 40L * 366L);
  }

  private static PlanVersion planWithVestingFullYears(int years) {
    return new PlanVersion(
        new OrganizationId(UUID.randomUUID()),
        "PLAN-1",
        LocalDate.of(2020, 1, 1),
        1,
        1,
        1,
        years,
        0,
        FundId.of(UUID.randomUUID()),
        Instant.parse("2020-01-01T00:00:00Z"));
  }
}

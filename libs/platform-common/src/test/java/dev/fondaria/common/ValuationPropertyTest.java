package dev.fondaria.common;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class ValuationPropertyTest {

  @Property(tries = 10000)
  void randomUnitsAndNavDoNotOverflowOrReturnNegative(
      @ForAll("realisticUnits") Units units, @ForAll("realisticNav") Nav nav) {
    Money result = Money.value(units, nav);

    assertThat(result.cents()).isGreaterThanOrEqualTo(0L);
    assertThat(result.currency()).isEqualTo(nav.currency());
  }

  @Provide
  Arbitrary<Units> realisticUnits() {
    // Realistic unit counts from 0 up to 1,000,000,000 units (scaled: 0 to 10^17)
    return Arbitraries.longs().between(0L, 100_000_000_000_000_000L).map(Units::of);
  }

  @Provide
  Arbitrary<Nav> realisticNav() {
    // Realistic NAV per unit from 0 up to 1,000,000 pesos/USD (scaled: 0 to 10^14)
    Arbitrary<Long> scaled = Arbitraries.longs().between(0L, 100_000_000_000_000L);
    Arbitrary<CurrencyCode> currencies = Arbitraries.of(CurrencyCode.class);
    return Combinators.combine(scaled, currencies).as(Nav::of);
  }
}

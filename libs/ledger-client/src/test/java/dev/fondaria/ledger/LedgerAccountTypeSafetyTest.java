package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the structural guarantee behind "reading a unit account's balance as Money must be
 * impossible at compile time": {@link LedgerAccount.Unit} has no accessor returning {@link Money},
 * and {@link LedgerAccount.Cash} has no accessor returning {@link Units}. If a future change ever
 * added one, it would restore the very bug this sealed-type split exists to prevent — this test
 * fails the moment that accessor exists, rather than waiting for a runtime mix-up.
 */
class LedgerAccountTypeSafetyTest {

  @Test
  void cashAccountHasNoUnitsAccessor() {
    assertThat(publicReturnTypesOf(LedgerAccount.Cash.class)).doesNotContain(Units.class);
    assertThat(publicReturnTypesOf(LedgerAccount.Cash.class)).contains(Money.class);
  }

  @Test
  void unitAccountHasNoMoneyAccessor() {
    assertThat(publicReturnTypesOf(LedgerAccount.Unit.class)).doesNotContain(Money.class);
    assertThat(publicReturnTypesOf(LedgerAccount.Unit.class)).contains(Units.class);
  }

  private static List<Class<?>> publicReturnTypesOf(Class<?> recordClass) {
    List<Class<?>> types = new ArrayList<>();
    for (Method method : recordClass.getMethods()) {
      types.add(method.getReturnType());
    }
    return types;
  }
}

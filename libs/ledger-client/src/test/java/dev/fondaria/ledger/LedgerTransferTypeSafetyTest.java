package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Same guarantee as {@link LedgerAccountTypeSafetyTest}, for the movement side: {@link
 * LedgerTransfer.Unit} has no accessor returning {@link Money}, and {@link LedgerTransfer.Cash} has
 * no accessor returning {@link Units}.
 */
class LedgerTransferTypeSafetyTest {

  @Test
  void cashTransferHasNoUnitsAccessor() {
    assertThat(publicReturnTypesOf(LedgerTransfer.Cash.class)).doesNotContain(Units.class);
    assertThat(publicReturnTypesOf(LedgerTransfer.Cash.class)).contains(Money.class);
  }

  @Test
  void unitTransferHasNoMoneyAccessor() {
    assertThat(publicReturnTypesOf(LedgerTransfer.Unit.class)).doesNotContain(Money.class);
    assertThat(publicReturnTypesOf(LedgerTransfer.Unit.class)).contains(Units.class);
  }

  private static List<Class<?>> publicReturnTypesOf(Class<?> recordClass) {
    List<Class<?>> types = new ArrayList<>();
    for (Method method : recordClass.getMethods()) {
      types.add(method.getReturnType());
    }
    return types;
  }
}

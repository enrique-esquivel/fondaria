package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountIdTest {

  @Test
  void ofAndTheCanonicalConstructorAgree() {
    assertThat(AccountId.of(1L, 2L)).isEqualTo(new AccountId(1L, 2L));
  }

  @Test
  void idsWithDifferentBitsAreNotEqual() {
    assertThat(AccountId.of(1L, 2L)).isNotEqualTo(AccountId.of(1L, 3L));
    assertThat(AccountId.of(1L, 2L)).isNotEqualTo(AccountId.of(2L, 2L));
  }

  @Test
  void theUnsignedDecimalPutsTheMostSignificantBitsInTheHighHalf() {
    assertThat(AccountId.of(1L, 0L).asUnsignedDecimal())
        .isEqualTo(BigInteger.ONE.shiftLeft(64).toString());
    assertThat(AccountId.of(0L, 1L).asUnsignedDecimal()).isEqualTo("1");
  }

  @Test
  void theUnsignedDecimalIsNeverNegative() {
    assertThat(AccountId.of(-1L, -1L).asUnsignedDecimal())
        .isEqualTo(BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE).toString());
  }

  /**
   * A derived id's decimal form is the numeric value of the UUID it came from — which is what makes
   * a movement lookup in {@code projection.transfer} find the rows the CDC stream wrote.
   */
  @Test
  void aDerivedIdReadsBackAsItsOwnUuidsNumericValue() {
    UUID uuid = UUID.randomUUID();
    BigInteger expected =
        BigInteger.valueOf(uuid.getMostSignificantBits())
            .and(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))
            .shiftLeft(64)
            .or(
                BigInteger.valueOf(uuid.getLeastSignificantBits())
                    .and(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)));

    assertThat(AccountIdDeriver.toAccountId(uuid).asUnsignedDecimal())
        .isEqualTo(expected.toString());
  }
}

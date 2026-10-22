package dev.fondaria.valuationapi.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A batch id is derived, never generated: redelivering a file must converge on one batch, and
 * providers redeliver as a matter of routine.
 */
class ValuationBatchIdTest {

  private static final String SOURCE = "ACME-CUSTODY";
  private static final String KEY = "valuations/ACME-CUSTODY/abc-file.csv";
  private static final String CHECKSUM = "a".repeat(64);

  @Test
  @DisplayName("the same three values always derive the same id")
  void isStable() {
    assertThat(ValuationBatchId.derive(SOURCE, KEY, CHECKSUM))
        .isEqualTo(ValuationBatchId.derive(SOURCE, KEY, CHECKSUM));
  }

  @Test
  @DisplayName("a different checksum is a different batch")
  void differsByChecksum() {
    assertThat(ValuationBatchId.derive(SOURCE, KEY, CHECKSUM))
        .isNotEqualTo(ValuationBatchId.derive(SOURCE, KEY, "b".repeat(64)));
  }

  @Test
  @DisplayName("a different object key is a different batch")
  void differsByObjectKey() {
    assertThat(ValuationBatchId.derive(SOURCE, KEY, CHECKSUM))
        .isNotEqualTo(ValuationBatchId.derive(SOURCE, KEY + "x", CHECKSUM));
  }

  @Test
  @DisplayName("two providers never share an id space")
  void differsBySource() {
    assertThat(ValuationBatchId.derive(SOURCE, KEY, CHECKSUM))
        .isNotEqualTo(ValuationBatchId.derive("OTHER-CUSTODY", KEY, CHECKSUM));
  }

  /**
   * The join of checksum and object key must be injective, or two different submissions could
   * derive one id and the second would silently be treated as a redelivery of the first.
   *
   * <p><b>The guarantee rests on the checksum being exactly 64 characters</b>, which {@code
   * SubmitValuationBatchRequest#requireChecksum} enforces before this is ever called. Because the
   * checksum leads and its length is fixed, the boundary between the two values sits at a fixed
   * offset and no crafted object key can shift it. Both pairs below are valid submissions — two
   * 64-character checksums — that a naive {@code objectKey + checksum} join would be at risk of
   * confusing.
   */
  @Test
  @DisplayName("a crafted object key cannot collide with another submission's id")
  void theJoinIsInjective() {
    UUID left = ValuationBatchId.derive(SOURCE, "xy", "a".repeat(64));
    UUID right = ValuationBatchId.derive(SOURCE, "y", "a".repeat(63) + "x");

    assertThat(left).isNotEqualTo(right);
  }

  @Test
  @DisplayName("a derived id is a version 5 UUID")
  void isVersionFive() {
    assertThat(ValuationBatchId.derive(SOURCE, KEY, CHECKSUM).version()).isEqualTo(5);
  }
}

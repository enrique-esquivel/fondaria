package dev.fondaria.contribution.batch.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.contribution.batch.BatchDeclaration;
import dev.fondaria.contribution.batch.ContributionSource;
import dev.fondaria.contribution.batch.InvalidBatchSubmissionException;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Request validation for the submission body — the object it points at, and the declaration the
 * async validator will hold the file to ({@code contribution-file-v2.md} §1.1).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SubmitContributionBatchRequestTest {

  private static final String VALID =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  private static SubmitContributionBatchRequest request(String objectKey, String checksum) {
    return new SubmitContributionBatchRequest(
        objectKey,
        checksum,
        "PLAN-ACME-CAJA-1",
        "2026-08-B",
        "2026-08-20",
        750_000L,
        2,
        Map.of("EMPLOYEE", 500_000L, "EMPLOYER", 250_000L, "VOLUNTARY", 0L));
  }

  private static SubmitContributionBatchRequest declaring(
      String valueDate, Long totalCents, Integer rowCount, Map<String, Long> sourceCents) {
    return new SubmitContributionBatchRequest(
        "k", VALID, "PLAN-1", "2026-08-B", valueDate, totalCents, rowCount, sourceCents);
  }

  @Test
  void a_lowercase_hex_digest_is_accepted() {
    assertThat(request("k", VALID).requireChecksum()).isEqualTo(VALID);
  }

  @Test
  void an_uppercase_digest_is_folded_rather_than_rejected() {
    // The same bytes must be the same batch however a producer spells its digest. Two
    // spellings creating two batches would defeat content-addressing entirely.
    assertThat(request("k", VALID.toUpperCase(java.util.Locale.ROOT)).requireChecksum())
        .isEqualTo(VALID);
  }

  @Test
  void surrounding_whitespace_is_trimmed() {
    assertThat(request("k", "  " + VALID + "\n").requireChecksum()).isEqualTo(VALID);
  }

  @Test
  void a_digest_of_the_wrong_length_is_rejected() {
    assertThatThrownBy(() -> request("k", VALID.substring(1)).requireChecksum())
        .isInstanceOf(InvalidBatchSubmissionException.class);
  }

  @Test
  void a_digest_containing_non_hex_characters_is_rejected() {
    assertThatThrownBy(() -> request("k", "z".repeat(64)).requireChecksum())
        .isInstanceOf(InvalidBatchSubmissionException.class);
  }

  @Test
  void an_absent_digest_is_rejected() {
    assertThatThrownBy(() -> request("k", null).requireChecksum())
        .isInstanceOf(InvalidBatchSubmissionException.class);
  }

  @Test
  void a_blank_object_key_is_rejected() {
    assertThatThrownBy(() -> request("   ", VALID).requireObjectKey())
        .isInstanceOf(InvalidBatchSubmissionException.class);
  }

  @Test
  void an_absurdly_long_object_key_is_rejected() {
    assertThatThrownBy(() -> request("k".repeat(1025), VALID).requireObjectKey())
        .isInstanceOf(InvalidBatchSubmissionException.class);
  }

  @Test
  void no_error_message_echoes_the_value_it_rejected() {
    // Caller-supplied text, and a problem body reaches every log it passes through.
    assertThatThrownBy(() -> request("k", "deadbeef-not-a-digest").requireChecksum())
        .hasMessageNotContaining("deadbeef");
  }

  @Nested
  class Declaration {

    private static final Map<String, Long> SOURCES =
        Map.of("EMPLOYEE", 500_000L, "EMPLOYER", 250_000L, "VOLUNTARY", 0L);

    @Test
    void a_complete_declaration_is_accepted() {
      BatchDeclaration declared = request("k", VALID).requireDeclaration();

      assertThat(declared.planId()).isEqualTo("PLAN-ACME-CAJA-1");
      assertThat(declared.payrollPeriod()).isEqualTo("2026-08-B");
      assertThat(declared.valueDate()).isEqualTo(LocalDate.of(2026, 8, 20));
      assertThat(declared.declaredTotalCents()).isEqualTo(750_000L);
      assertThat(declared.declaredRowCount()).isEqualTo(2);
      assertThat(declared.declaredCentsFor(ContributionSource.EMPLOYER)).isEqualTo(250_000L);
    }

    @Test
    void an_omitted_source_is_declared_as_zero_rather_than_unknown() {
      // "I did not mention voluntary money" and "I am sending no voluntary money" have to
      // be the same statement, or a file could carry a column nobody declared and still
      // tie out.
      BatchDeclaration declared =
          declaring("2026-08-20", 100L, 1, Map.of("EMPLOYEE", 100L)).requireDeclaration();

      assertThat(declared.declaredCentsFor(ContributionSource.VOLUNTARY)).isZero();
      assertThat(declared.declaredSourceCents()).hasSize(ContributionSource.values().length);
    }

    @Test
    void an_unparseable_value_date_is_rejected() {
      assertThatThrownBy(() -> declaring("20/08/2026", 100L, 1, SOURCES).requireDeclaration())
          .isInstanceOf(InvalidBatchSubmissionException.class);
    }

    @Test
    void an_absent_total_is_rejected() {
      assertThatThrownBy(() -> declaring("2026-08-20", null, 1, SOURCES).requireDeclaration())
          .isInstanceOf(InvalidBatchSubmissionException.class);
    }

    @Test
    void a_negative_total_is_rejected() {
      assertThatThrownBy(() -> declaring("2026-08-20", -1L, 1, SOURCES).requireDeclaration())
          .isInstanceOf(InvalidBatchSubmissionException.class);
    }

    @Test
    void a_zero_row_count_is_rejected() {
      // A batch with no rows is not a batch, and refusing it here gives the employer a
      // 400 naming the field rather than a rejection minutes later.
      assertThatThrownBy(() -> declaring("2026-08-20", 100L, 0, SOURCES).requireDeclaration())
          .isInstanceOf(InvalidBatchSubmissionException.class);
    }

    @Test
    void a_negative_source_total_is_rejected() {
      assertThatThrownBy(
              () ->
                  declaring("2026-08-20", 100L, 1, Map.of("EMPLOYEE", -100L)).requireDeclaration())
          .isInstanceOf(InvalidBatchSubmissionException.class);
    }

    @Test
    void an_unknown_source_key_is_rejected_without_echoing_it() {
      assertThatThrownBy(
              () ->
                  declaring("2026-08-20", 100L, 1, Map.of("BONUS-SECRET", 100L))
                      .requireDeclaration())
          .isInstanceOf(InvalidBatchSubmissionException.class)
          .hasMessageNotContaining("BONUS-SECRET");
    }

    @Test
    void a_blank_plan_id_is_rejected() {
      assertThatThrownBy(
              () ->
                  new SubmitContributionBatchRequest(
                          "k", VALID, "  ", "2026-08-B", "2026-08-20", 100L, 1, SOURCES)
                      .requireDeclaration())
          .isInstanceOf(InvalidBatchSubmissionException.class);
    }
  }
}

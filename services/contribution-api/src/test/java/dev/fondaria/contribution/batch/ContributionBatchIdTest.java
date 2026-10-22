package dev.fondaria.contribution.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.OrganizationId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The derivation submission idempotency rests on. Every property below is one a random id would
 * break, and breaking any of them means a resubmitted file becomes a second batch — which is the
 * one thing this endpoint promises cannot happen.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ContributionBatchIdTest {

  private static final OrganizationId ORGANIZATION = new OrganizationId(UUID.randomUUID());
  private static final String KEY = "contribution-batches/org/2026/08/file.csv";
  private static final String CHECKSUM =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  @Test
  void the_same_file_derives_the_same_id_every_time() {
    assertThat(ContributionBatchId.derive(ORGANIZATION, KEY, CHECKSUM))
        .isEqualTo(ContributionBatchId.derive(ORGANIZATION, KEY, CHECKSUM));
  }

  @Test
  void a_different_checksum_is_a_different_batch() {
    // A different body is a different checksum, and a different checksum is a different
    // batch. That is why this endpoint needs no "same key, different body" 422.
    assertThat(ContributionBatchId.derive(ORGANIZATION, KEY, CHECKSUM))
        .isNotEqualTo(
            ContributionBatchId.derive(
                ORGANIZATION,
                KEY,
                "0000000000000000000000000000000000000000000000000000000000000000"));
  }

  @Test
  void a_different_object_key_is_a_different_batch() {
    assertThat(ContributionBatchId.derive(ORGANIZATION, KEY, CHECKSUM))
        .isNotEqualTo(ContributionBatchId.derive(ORGANIZATION, KEY + "x", CHECKSUM));
  }

  @Test
  void one_organizations_batch_id_can_never_collide_with_anothers() {
    // The namespace is the organization's own id, so the id space is per-tenant by
    // construction rather than by a prefix somebody has to remember to include.
    assertThat(ContributionBatchId.derive(ORGANIZATION, KEY, CHECKSUM))
        .isNotEqualTo(
            ContributionBatchId.derive(new OrganizationId(UUID.randomUUID()), KEY, CHECKSUM));
  }

  @Test
  void the_key_and_the_checksum_cannot_be_confused_with_each_other() {
    // Joined with a unit separator rather than concatenated, so no pair of (key,
    // checksum) values can be rearranged into another pair with the same joined string.
    assertThat(ContributionBatchId.derive(ORGANIZATION, "a", "b"))
        .isNotEqualTo(ContributionBatchId.derive(ORGANIZATION, "ab", ""));
  }

  @Test
  void it_is_a_version_5_uuid() {
    assertThat(ContributionBatchId.derive(ORGANIZATION, KEY, CHECKSUM).version()).isEqualTo(5);
  }

  @Test
  void a_parts_id_is_the_same_on_every_attempt_at_staging_the_same_file() {
    // Staging is re-run after a crash or a stale lease. A generated part id would create a
    // second set of parts on the second attempt and stage every participant twice.
    UUID file = ContributionBatchId.derive(ORGANIZATION, KEY, CHECKSUM);

    assertThat(ContributionBatchId.derivePart(file, 7))
        .isEqualTo(ContributionBatchId.derivePart(file, 7))
        .isNotEqualTo(ContributionBatchId.derivePart(file, 8));
  }

  @Test
  void two_files_parts_can_never_collide_because_the_namespace_is_the_file() {
    UUID file = ContributionBatchId.derive(ORGANIZATION, KEY, CHECKSUM);
    UUID otherFile = ContributionBatchId.derive(ORGANIZATION, KEY + "x", CHECKSUM);

    assertThat(ContributionBatchId.derivePart(file, 2))
        .isNotEqualTo(ContributionBatchId.derivePart(otherFile, 2));
  }

  @Test
  void a_part_id_is_never_the_file_it_belongs_to() {
    UUID file = ContributionBatchId.derive(ORGANIZATION, KEY, CHECKSUM);

    assertThat(ContributionBatchId.derivePart(file, 2)).isNotEqualTo(file);
  }

  @Test
  void part_one_has_no_derived_id_because_it_is_the_submitted_batch() {
    UUID file = ContributionBatchId.derive(ORGANIZATION, KEY, CHECKSUM);

    assertThatThrownBy(() -> ContributionBatchId.derivePart(file, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("part 1");
  }
}

package dev.fondaria.contribution.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.plan.InMemoryPlanVersionRepository;
import dev.fondaria.rules.PlanVersion;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * Checks 3 to 6 of {@code contribution-file-v2.md} §4 — does the file bear out what the submission
 * declared — including the fact that they run <b>in that order</b>, which is the part a reviewer
 * cannot see from the code alone and the part that decides whether an employer gets an answer it
 * can act on. Also checks 7 and 8, step 5's plan-rule checks (BACKLOG.md): does the declared plan
 * resolve, and does every row honour its matching ratio.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ContributionBatchValidatorTest {

  private final OrganizationId organization = new OrganizationId(UUID.randomUUID());
  private final InMemoryPlanVersionRepository planVersions = new InMemoryPlanVersionRepository();
  private final ContributionBatchValidator validator = new ContributionBatchValidator(planVersions);

  @BeforeEach
  void publishDefaultPlan() {
    planVersions.publish(
        ContributionFiles.defaultPlanVersion(organization, FundId.of(UUID.randomUUID())));
  }

  private BatchRejection rejectionOf(String content, BatchDeclaration declared) {
    return rejectionOf(organization, content, declared);
  }

  private BatchRejection rejectionOf(
      OrganizationId organizationId, String content, BatchDeclaration declared) {
    ContributionFile file = ContributionFileParser.parse(content.getBytes(StandardCharsets.UTF_8));
    try {
      validator.validate(organizationId, file, declared);
    } catch (BatchRejectedException e) {
      return e.rejection();
    }
    throw new AssertionError("Expected a whole-batch rejection, and the batch was accepted");
  }

  private static String rows(String... rows) {
    return ContributionFiles.columnHeader() + "\n" + String.join("\n", rows) + "\n";
  }

  @Test
  void the_worked_example_of_section_5_is_accepted() {
    ContributionFile file =
        ContributionFileParser.parse(
            ContributionFiles.workedExample().getBytes(StandardCharsets.UTF_8));

    assertThatCode(
            () ->
                validator.validate(
                    organization, file, ContributionFiles.workedExampleDeclaration()))
        .doesNotThrowAnyException();
  }

  @Test
  void check_3_a_declared_row_count_that_is_not_the_actual_count_is_rejected() {
    BatchRejection rejection =
        rejectionOf(
            rows(
                "P-1,PAY-1,2019-03-01,2026-01-15,100,0,0",
                "P-2,PAY-2,2020-06-01,2026-01-15,200,0,0"),
            ContributionFiles.declaration(300L, 3, 300L, 0L, 0L));

    assertThat(rejection.code()).isEqualTo(BatchRejectionCode.ROW_COUNT_MISMATCH);
    assertThat(rejection.details())
        .containsEntry("declaredRowCount", 3)
        .containsEntry("actualRowCount", 2);
  }

  @Test
  void check_4_two_rows_for_the_same_participant_are_rejected() {
    // One rule, because there is one row shape. A second row for a participant is a
    // duplicated declaration whose copies may disagree about the dates as well as the
    // money.
    BatchRejection rejection =
        rejectionOf(
            rows(
                "P-1,PAY-1,2019-03-01,2026-01-15,100,0,0",
                "P-1,PAY-1,2021-09-01,2026-01-15,200,0,0"),
            ContributionFiles.declaration(300L, 2, 300L, 0L, 0L));

    assertThat(rejection.code()).isEqualTo(BatchRejectionCode.DUPLICATE_ROW);
  }

  @Test
  void check_4_one_participant_contributing_from_two_sources_is_not_a_duplicate() {
    // Under v1 this WAS two rows and needed an explicit carve-out so it was not read as a
    // duplicate. Here it is one row with two populated columns and the question does not
    // arise — which is the simplification the format was chosen for.
    ContributionFile file =
        ContributionFileParser.parse(
            ContributionFiles.workedExample().getBytes(StandardCharsets.UTF_8));

    assertThat(file.rows()).hasSize(2);
    assertThatCode(
            () ->
                validator.validate(
                    organization, file, ContributionFiles.workedExampleDeclaration()))
        .doesNotThrowAnyException();
  }

  @Test
  void check_5_a_source_total_that_is_not_the_sum_of_its_column_names_the_source() {
    // Task 3.1 requires employee and employer money to stay separately represented end to
    // end, and that includes how a discrepancy is reported: naming the source is what
    // makes the answer actionable.
    BatchRejection rejection =
        rejectionOf(
            rows(
                "P-001,ACME-EMP-0142,2019-03-01,2026-01-15,300000,149900,0",
                "P-002,ACME-EMP-0891,2026-02-01,2026-02-01,200000,100000,0"),
            ContributionFiles.workedExampleDeclaration());

    assertThat(rejection.code()).isEqualTo(BatchRejectionCode.SOURCE_SUBTOTAL_MISMATCH);
    assertThat(rejection.details())
        .containsEntry("source", "EMPLOYER")
        .containsEntry("declaredCents", 250_000L)
        .containsEntry("actualCents", 249_900L);
  }

  @Test
  void check_5_an_unused_source_declared_as_non_zero_is_rejected() {
    // The zero column has to be checked like any other, or an employer could declare
    // voluntary money it never itemised and have the grand total still tie out.
    BatchRejection rejection =
        rejectionOf(
            rows("P-1,PAY-1,2019-03-01,2026-01-15,100,0,0"),
            ContributionFiles.declaration(300L, 1, 100L, 0L, 200L));

    assertThat(rejection.code()).isEqualTo(BatchRejectionCode.SOURCE_SUBTOTAL_MISMATCH);
    assertThat(rejection.details())
        .containsEntry("source", "VOLUNTARY")
        .containsEntry("declaredCents", 200L)
        .containsEntry("actualCents", 0L);
  }

  @Test
  void check_5_a_source_omitted_from_the_declaration_is_held_to_zero() {
    // BatchDeclaration fills an omitted source with 0 rather than treating it as unknown.
    // "I did not mention voluntary money" and "I am sending no voluntary money" have to be
    // the same statement, or a file could carry an unmatched column nobody declared.
    BatchDeclaration omitted =
        new BatchDeclaration(
            ContributionFiles.PLAN_ID,
            ContributionFiles.PAYROLL_PERIOD,
            ContributionFiles.VALUE_DATE,
            100L,
            1,
            java.util.Map.of(ContributionSource.EMPLOYEE, 100L));

    BatchRejection rejection =
        rejectionOf(rows("P-1,PAY-1,2019-03-01,2026-01-15,100,0,50"), omitted);

    assertThat(rejection.code()).isEqualTo(BatchRejectionCode.SOURCE_SUBTOTAL_MISMATCH);
    assertThat(rejection.details())
        .containsEntry("source", "VOLUNTARY")
        .containsEntry("declaredCents", 0L)
        .containsEntry("actualCents", 50L);
  }

  @Test
  void check_6_a_grand_total_that_is_not_the_sum_of_the_source_totals_is_rejected() {
    BatchRejection rejection =
        rejectionOf(
            ContributionFiles.workedExample(),
            ContributionFiles.declaration(760_000L, 2, 500_000L, 250_000L, 0L));

    assertThat(rejection.code()).isEqualTo(BatchRejectionCode.DECLARED_TOTAL_MISMATCH);
    assertThat(rejection.details())
        .containsEntry("declaredTotalCents", 760_000L)
        .containsEntry("sourceTotalSumCents", 750_000L);
  }

  @Test
  void a_truncated_file_is_reported_as_a_row_count_mismatch_not_a_source_total_mismatch() {
    // The order in §4 is the contract, not an optimisation. Both checks would fail on
    // this file; only one of the two answers tells the employer what actually happened.
    BatchRejection rejection =
        rejectionOf(
            rows("P-001,ACME-EMP-0142,2019-03-01,2026-01-15,300000,150000,0"),
            ContributionFiles.workedExampleDeclaration());

    assertThat(rejection.code()).isEqualTo(BatchRejectionCode.ROW_COUNT_MISMATCH);
  }

  @Test
  void a_duplicate_row_is_reported_before_the_totals_it_also_breaks() {
    // Check 4 before check 5. A duplicated participant usually breaks a column sum too,
    // and "you sent this participant twice" is the answer that leads somewhere.
    BatchRejection rejection =
        rejectionOf(
            rows(
                "P-1,PAY-1,2019-03-01,2026-01-15,100,0,0",
                "P-1,PAY-1,2019-03-01,2026-01-15,100,0,0"),
            ContributionFiles.declaration(100L, 2, 100L, 0L, 0L));

    assertThat(rejection.code()).isEqualTo(BatchRejectionCode.DUPLICATE_ROW);
  }

  @Test
  void no_rejection_message_or_detail_ever_carries_a_participant_reference() {
    BatchRejection rejection =
        rejectionOf(
            rows(
                "P-SECRET-0142,PAY-SECRET-9,2019-03-01,2026-01-15,100,0,0",
                "P-SECRET-0142,PAY-SECRET-9,2019-03-01,2026-01-15,200,0,0"),
            ContributionFiles.declaration(300L, 2, 300L, 0L, 0L));

    assertThat(rejection.code()).isEqualTo(BatchRejectionCode.DUPLICATE_ROW);
    assertThat(rejection.message()).doesNotContain("SECRET").doesNotContain("2019-03-01");
    assertThat(rejection.details().values())
        .allSatisfy(
            value ->
                assertThat(value.toString()).doesNotContain("SECRET").doesNotContain("2019-03-01"));
  }

  @Test
  void every_amount_in_a_rejection_detail_is_an_integer_never_a_decimal() {
    // The OpenAPI contract says so, and a decimal here would be the first float in a
    // money path (AGENTS.md §4).
    BatchRejection rejection =
        rejectionOf(
            rows("P-1,PAY-1,2019-03-01,2026-01-15,300,0,0"),
            ContributionFiles.declaration(301L, 1, 301L, 0L, 0L));

    assertThat(rejection.details().values())
        .allSatisfy(value -> assertThat(value).isNotInstanceOfAny(Double.class, Float.class));
    assertThat(rejection.details().get("declaredCents")).isInstanceOf(Long.class);
  }

  @Test
  void check_7_a_batch_declaring_a_plan_with_no_resolvable_version_is_rejected() {
    // A fresh organization with nothing published for its plan at all.
    OrganizationId unknownPlanOrganization = new OrganizationId(UUID.randomUUID());

    BatchRejection rejection =
        rejectionOf(
            unknownPlanOrganization,
            ContributionFiles.workedExample(),
            ContributionFiles.workedExampleDeclaration());

    assertThat(rejection.code()).isEqualTo(BatchRejectionCode.PLAN_VERSION_NOT_FOUND);
  }

  @Test
  void check_7_a_plan_version_effective_only_after_the_value_date_does_not_resolve() {
    OrganizationId futurePlanOrganization = new OrganizationId(UUID.randomUUID());
    planVersions.publish(
        new PlanVersion(
            futurePlanOrganization,
            ContributionFiles.PLAN_ID,
            ContributionFiles.VALUE_DATE.plusDays(1),
            1,
            1,
            2,
            5,
            0,
            FundId.of(UUID.randomUUID()),
            Instant.parse("2020-01-01T00:00:00Z")));

    BatchRejection rejection =
        rejectionOf(
            futurePlanOrganization,
            ContributionFiles.workedExample(),
            ContributionFiles.workedExampleDeclaration());

    assertThat(rejection.code()).isEqualTo(BatchRejectionCode.PLAN_VERSION_NOT_FOUND);
  }

  @Test
  void check_8_a_row_whose_employer_amount_breaches_the_matching_ratio_is_rejected() {
    // The default plan requires the employer to contribute half the employee amount
    // (contribution-file-v2.md §5's own worked example ratio). P-002 breaks it here.
    BatchRejection rejection =
        rejectionOf(
            rows(
                "P-001,ACME-EMP-0142,2019-03-01,2026-01-15,300000,150000,0",
                "P-002,ACME-EMP-0891,2026-02-01,2026-02-01,200000,140000,0"),
            ContributionFiles.declaration(790_000L, 2, 500_000L, 290_000L, 0L));

    assertThat(rejection.code()).isEqualTo(BatchRejectionCode.EMPLOYER_MATCH_RATIO_BREACH);
    assertThat(rejection.details()).containsEntry("breachingRowCount", 1);
  }

  @Test
  void check_8_never_names_which_row_breached() {
    BatchRejection rejection =
        rejectionOf(
            rows("P-SECRET,PAY-SECRET,2019-03-01,2026-01-15,300000,0,0"),
            ContributionFiles.declaration(300_000L, 1, 300_000L, 0L, 0L));

    assertThat(rejection.code()).isEqualTo(BatchRejectionCode.EMPLOYER_MATCH_RATIO_BREACH);
    assertThat(rejection.message()).doesNotContain("SECRET");
    assertThat(rejection.details().values())
        .allSatisfy(value -> assertThat(value.toString()).doesNotContain("SECRET"));
  }

  @Test
  void check_8_runs_after_every_structural_check() {
    // A row-count mismatch is reported even though the same file also breaches parity —
    // the structural checks still come first.
    BatchRejection rejection =
        rejectionOf(
            rows("P-1,PAY-1,2019-03-01,2026-01-15,300000,0,0"),
            ContributionFiles.declaration(300_000L, 9, 300_000L, 0L, 0L));

    assertThat(rejection.code()).isEqualTo(BatchRejectionCode.ROW_COUNT_MISMATCH);
  }

  @Test
  void a_plan_naming_a_different_plan_id_does_not_resolve_this_organizations_default_plan() {
    BatchDeclaration declaredUnderAnotherPlan =
        new BatchDeclaration(
            "PLAN-SOMEONE-ELSE",
            ContributionFiles.PAYROLL_PERIOD,
            ContributionFiles.VALUE_DATE,
            ContributionFiles.WORKED_EXAMPLE_TOTAL_CENTS,
            2,
            java.util.Map.of(
                ContributionSource.EMPLOYEE, 500_000L, ContributionSource.EMPLOYER, 250_000L));

    BatchRejection rejection =
        rejectionOf(ContributionFiles.workedExample(), declaredUnderAnotherPlan);

    assertThat(rejection.code()).isEqualTo(BatchRejectionCode.PLAN_VERSION_NOT_FOUND);
  }
}

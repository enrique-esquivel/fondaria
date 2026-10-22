package dev.fondaria.contribution.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.ledger.LedgerLimits;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@link BatchPartitioner} — the arithmetic ADR-0017 rests on.
 *
 * <p>The property every test here is really asserting is one thing: <b>no part can produce a linked
 * chain longer than the ledger will accept.</b> A chain over the limit is not slow or partially
 * applied — the request never forms — so a partition that is one row too generous is the difference
 * between a file allotting and a file that cannot be allotted at all.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class BatchPartitionerTest {

  private static final long EMPLOYEE_CENTS = 300_000L;
  private static final long EMPLOYER_CENTS = 150_000L;
  private static final long VOLUNTARY_CENTS = 50_000L;

  @Test
  void a_file_that_fits_in_one_chain_is_not_divided() {
    List<ContributionFile.ParticipantRow> rows = rows(500, EMPLOYEE_CENTS, EMPLOYER_CENTS, 0L);

    List<List<ContributionFile.ParticipantRow>> parts = BatchPartitioner.partition(rows, 4_093);

    assertThat(parts).hasSize(1);
    assertThat(parts.get(0)).isEqualTo(rows);
  }

  @Test
  void a_two_source_file_is_divided_at_the_row_cap() {
    // 4,093 rows × 2 legs + 2 cash legs is 8,188, one under the ledger's ceiling — which is
    // where TASK-LOG.md step 40's "roughly 4,093 rows" comes from.
    List<ContributionFile.ParticipantRow> rows = rows(4_094, EMPLOYEE_CENTS, EMPLOYER_CENTS, 0L);

    List<List<ContributionFile.ParticipantRow>> parts = BatchPartitioner.partition(rows, 4_093);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0)).hasSize(4_093);
    assertThat(parts.get(1)).hasSize(1);
    assertThat(chainLengthOf(parts.get(0)))
        .isEqualTo(8_188)
        .isLessThanOrEqualTo(LedgerLimits.MAX_LINKED_CHAIN_LENGTH);
  }

  /**
   * The bug the row cap alone would have shipped. {@code ContributionSource} has three members, so
   * a row carrying a voluntary lot as well costs three transfers, and 4,093 of them is 12,281 —
   * half as far over the wall again as the 200,000-row file that found it.
   */
  @Test
  void a_three_source_file_is_divided_before_the_row_cap_is_reached() {
    List<ContributionFile.ParticipantRow> rows =
        rows(4_093, EMPLOYEE_CENTS, EMPLOYER_CENTS, VOLUNTARY_CENTS);

    List<List<ContributionFile.ParticipantRow>> parts = BatchPartitioner.partition(rows, 4_093);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0)).hasSize(2_729);
    for (List<ContributionFile.ParticipantRow> part : parts) {
      assertThat(chainLengthOf(part)).isLessThanOrEqualTo(LedgerLimits.MAX_LINKED_CHAIN_LENGTH);
    }
  }

  /**
   * The other direction, and the reason the row cap is not redundant: at one leg per row the leg
   * budget alone would allow parts of 8,187 rows.
   */
  @Test
  void an_employee_only_file_is_divided_at_the_row_cap_not_the_leg_budget() {
    List<ContributionFile.ParticipantRow> rows = rows(8_186, EMPLOYEE_CENTS, 0L, 0L);

    List<List<ContributionFile.ParticipantRow>> parts = BatchPartitioner.partition(rows, 4_093);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0)).hasSize(4_093);
    assertThat(parts.get(1)).hasSize(4_093);
  }

  @Test
  void a_zero_amount_costs_no_leg_and_so_no_part_is_closed_for_it() {
    // Every row here carries one paying source and two zeros. Allotment posts nothing for a
    // zero amount, so budgeting for one would divide a file into twice the parts it needs.
    List<ContributionFile.ParticipantRow> rows = rows(4_000, 0L, EMPLOYER_CENTS, 0L);

    List<List<ContributionFile.ParticipantRow>> parts = BatchPartitioner.partition(rows, 4_093);

    assertThat(parts).hasSize(1);
  }

  @Test
  void every_row_appears_exactly_once_and_in_file_order() {
    List<ContributionFile.ParticipantRow> rows =
        rows(10_000, EMPLOYEE_CENTS, EMPLOYER_CENTS, VOLUNTARY_CENTS);

    List<ContributionFile.ParticipantRow> flattened = new ArrayList<>();
    BatchPartitioner.partition(rows, 4_093).forEach(flattened::addAll);

    assertThat(flattened).containsExactlyElementsOf(rows);
  }

  /**
   * Staging is re-runnable, and rows are idempotent on {@code (organization, batch, participant)} —
   * a key that only protects a participant who lands in the same part every time. A partition that
   * varied between attempts would stage someone twice, which is a doubled contribution.
   */
  @Test
  void the_same_file_and_the_same_bounds_partition_identically_every_time() {
    List<ContributionFile.ParticipantRow> rows =
        rows(9_000, EMPLOYEE_CENTS, EMPLOYER_CENTS, VOLUNTARY_CENTS);

    assertThat(BatchPartitioner.partition(rows, 4_093))
        .isEqualTo(BatchPartitioner.partition(rows, 4_093));
  }

  @Test
  void a_row_cap_of_one_puts_every_row_in_its_own_part() {
    List<ContributionFile.ParticipantRow> rows = rows(3, EMPLOYEE_CENTS, EMPLOYER_CENTS, 0L);

    assertThat(BatchPartitioner.partition(rows, 1)).hasSize(3);
  }

  @Test
  void a_non_positive_row_cap_is_refused_rather_than_looping_forever() {
    List<ContributionFile.ParticipantRow> rows = rows(2, EMPLOYEE_CENTS, EMPLOYER_CENTS, 0L);

    assertThatThrownBy(() -> BatchPartitioner.partition(rows, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxRowsPerPart");
  }

  /**
   * Two cash legs plus one unit leg per paying source — the chain {@code AllotmentService} posts.
   */
  private static int chainLengthOf(List<ContributionFile.ParticipantRow> part) {
    int legs = 2;
    for (ContributionFile.ParticipantRow row : part) {
      for (ContributionSource source : ContributionSource.values()) {
        if (row.amountOf(source) > 0) {
          legs++;
        }
      }
    }
    return legs;
  }

  private static List<ContributionFile.ParticipantRow> rows(
      int count, long employee, long employer, long voluntary) {
    Map<ContributionSource, Long> amounts = new EnumMap<>(ContributionSource.class);
    amounts.put(ContributionSource.EMPLOYEE, employee);
    amounts.put(ContributionSource.EMPLOYER, employer);
    amounts.put(ContributionSource.VOLUNTARY, voluntary);

    List<ContributionFile.ParticipantRow> rows = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      rows.add(
          new ContributionFile.ParticipantRow(
              "P-" + i,
              "EMP-" + i,
              LocalDate.of(2020, 1, 1),
              LocalDate.of(2026, 1, 1),
              Map.copyOf(amounts)));
    }
    return List.copyOf(rows);
  }
}

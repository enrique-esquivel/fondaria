package dev.fondaria.demo.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContributionFileGeneratorTest {

  private static final LocalDate VALUE_DATE = LocalDate.of(2026, 10, 6);

  @Test
  void headerMatchesContributionFileV2() {
    ContributionFileGenerator.GeneratedFile file =
        ContributionFileGenerator.generate(1L, 0, 5, VALUE_DATE, 1, 1, 0);
    assertThat(file.csv().lines().findFirst()).contains(ContributionFileGenerator.COLUMN_HEADER);
  }

  @Test
  void oneDataRowPerParticipant() {
    ContributionFileGenerator.GeneratedFile file =
        ContributionFileGenerator.generate(1L, 0, 250, VALUE_DATE, 1, 1, 0);
    long dataRows = file.csv().lines().count() - 1;
    assertThat(dataRows).isEqualTo(250);
    assertThat(file.rowCount()).isEqualTo(250);
  }

  @Test
  void declaredTotalsMatchWhatWasWritten() {
    ContributionFileGenerator.GeneratedFile file =
        ContributionFileGenerator.generate(7L, 3, 1_000, VALUE_DATE, 1, 2, 0);

    long employeeFromRows = 0;
    long employerFromRows = 0;
    List<String> lines = file.csv().lines().skip(1).toList();
    for (String line : lines) {
      String[] columns = line.split(",");
      employeeFromRows += Long.parseLong(columns[4]);
      employerFromRows += Long.parseLong(columns[5]);
      assertThat(columns[6]).isEqualTo("0"); // voluntaryCents always zero
    }

    assertThat(file.declaredSourceCents().get("EMPLOYEE")).isEqualTo(employeeFromRows);
    assertThat(file.declaredSourceCents().get("EMPLOYER")).isEqualTo(employerFromRows);
    assertThat(file.declaredSourceCents().get("VOLUNTARY")).isEqualTo(0L);
    assertThat(file.declaredTotalCents()).isEqualTo(employeeFromRows + employerFromRows);
  }

  @Test
  void everyRowSatisfiesTheMatchingRatioExactly() {
    int numerator = 2;
    int denominator = 3;
    ContributionFileGenerator.GeneratedFile file =
        ContributionFileGenerator.generate(42L, 5, 500, VALUE_DATE, numerator, denominator, 0);

    for (String line : file.csv().lines().skip(1).toList()) {
      String[] columns = line.split(",");
      long employeeCents = Long.parseLong(columns[4]);
      long employerCents = Long.parseLong(columns[5]);
      long expected = expectedEmployerCents(employeeCents, numerator, denominator);
      assertThat(employerCents).as("row: %s", line).isEqualTo(expected);
    }
  }

  @Test
  void employmentStartNeverAfterEnrolmentDate() {
    ContributionFileGenerator.GeneratedFile file =
        ContributionFileGenerator.generate(9L, 0, 100, VALUE_DATE, 1, 1, 0);
    for (String line : file.csv().lines().skip(1).toList()) {
      String[] columns = line.split(",");
      LocalDate employmentStart = LocalDate.parse(columns[2]);
      LocalDate enrolmentDate = LocalDate.parse(columns[3]);
      assertThat(employmentStart).isBeforeOrEqualTo(enrolmentDate);
    }
  }

  @Test
  void isDeterministicForTheSameSeedAndOrganization() {
    ContributionFileGenerator.GeneratedFile first =
        ContributionFileGenerator.generate(3L, 1, 50, VALUE_DATE, 1, 1, 0);
    ContributionFileGenerator.GeneratedFile second =
        ContributionFileGenerator.generate(3L, 1, 50, VALUE_DATE, 1, 1, 0);
    assertThat(second.csv()).isEqualTo(first.csv());
  }

  @Test
  void heroCountIsCappedAtRequestedCount() {
    ContributionFileGenerator.GeneratedFile file =
        ContributionFileGenerator.generate(1L, 0, 2, VALUE_DATE, 1, 1, 5);
    assertThat(file.heroInternalRefs()).hasSize(2); // fewer participants than heroes requested
  }

  @Test
  void corruptedTotalIsExactlyOneCentOff() {
    ContributionFileGenerator.GeneratedFile file =
        ContributionFileGenerator.generate(1L, 0, 20, VALUE_DATE, 1, 1, 0);
    assertThat(ContributionFileGenerator.corruptedDeclaredTotalCents(file))
        .isEqualTo(file.declaredTotalCents() - 1);
  }

  private static long expectedEmployerCents(long employeeCents, int numerator, int denominator) {
    long doubledNumerator = employeeCents * numerator * 2L;
    long doubledDenominator = (long) denominator * 2L;
    return (doubledNumerator + denominator) / doubledDenominator;
  }
}

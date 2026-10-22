package dev.fondaria.contribution.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Check 2 of {@code contribution-file-v2.md} §4 — the file parses — and nothing else. Every failure
 * here is {@code MALFORMED_FILE}; the checks that compare the file against what the submission
 * declared have their own codes and their own test.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ContributionFileParserTest {

  private static ContributionFile parse(String content) {
    return ContributionFileParser.parse(content.getBytes(StandardCharsets.UTF_8));
  }

  private static void assertMalformed(String content) {
    assertThatThrownBy(() -> parse(content))
        .isInstanceOf(BatchRejectedException.class)
        .extracting(e -> ((BatchRejectedException) e).rejection().code())
        .isEqualTo(BatchRejectionCode.MALFORMED_FILE);
  }

  /** A correct one-row file, so a test can vary exactly one thing about it. */
  private static String oneRow(String row) {
    return ContributionFiles.columnHeader() + "\n" + row + "\n";
  }

  @Test
  void the_worked_example_of_section_5_parses_into_its_stated_shape() {
    ContributionFile file = parse(ContributionFiles.workedExample());

    assertThat(file.rows()).hasSize(2);
    assertThat(file.rows())
        .extracting(ContributionFile.ParticipantRow::participantInternalRef)
        .containsExactly("P-001", "P-002");
  }

  @Test
  void one_participant_is_one_row_carrying_every_source() {
    // The point of the format: a participant with an employee deduction and an employer
    // match is ONE row with two populated columns.
    ContributionFile.ParticipantRow row = parse(ContributionFiles.workedExample()).rows().get(0);

    assertThat(row.participantInternalRef()).isEqualTo("P-001");
    assertThat(row.participantPayrollRef()).isEqualTo("ACME-EMP-0142");
    assertThat(row.amountOf(ContributionSource.EMPLOYEE)).isEqualTo(300_000L);
    assertThat(row.amountOf(ContributionSource.EMPLOYER)).isEqualTo(150_000L);
    assertThat(row.amountOf(ContributionSource.VOLUNTARY)).isZero();
  }

  @Test
  void the_file_declares_nothing_about_the_batch_itself() {
    // §1.1: no plan, no period, no value date, no totals, no row count. A parsed file is
    // a list of participants and that is all it is. Everything else arrives with the
    // submission, and ContributionBatchValidator is what compares the two.
    ContributionFile file = parse(ContributionFiles.workedExample());

    assertThat(file.getClass().getRecordComponents())
        .as("a parsed file has exactly one component, and it is the rows")
        .singleElement()
        .satisfies(component -> assertThat(component.getName()).isEqualTo("rows"));
  }

  @Nested
  class ColumnHeaderRow {

    @Test
    void the_header_names_every_column_in_order() {
      assertThat(ContributionFiles.columnHeader())
          .isEqualTo(
              "participantInternalRef,participantPayrollRef,employmentStartDate,enrolmentDate,"
                  + "employeeCents,employerCents,voluntaryCents");
    }

    @Test
    void a_file_with_no_column_header_is_malformed() {
      assertMalformed("P-001,ACME-EMP-0142,2019-03-01,2026-01-15,300000,150000,0\n");
    }

    @Test
    void a_header_with_the_amount_columns_reordered_is_malformed() {
      // The reason §2 matches exactly rather than leniently: swap two amount columns and
      // every row still parses, every total still ties out, and employer money lands in
      // the voluntary column. A silent misallocation, not a parse error — unless this
      // check refuses it.
      assertMalformed(
          "participantInternalRef,participantPayrollRef,employmentStartDate,enrolmentDate,"
              + "employerCents,employeeCents,voluntaryCents\n"
              + "P-001,ACME-EMP-0142,2019-03-01,2026-01-15,150000,300000,0\n");
    }

    @Test
    void a_header_with_a_renamed_column_is_malformed() {
      assertMalformed(
          "participantInternalRef,payrollRef,employmentStartDate,enrolmentDate,"
              + "employeeCents,employerCents,voluntaryCents\n"
              + "P-001,ACME-EMP-0142,2019-03-01,2026-01-15,300000,150000,0\n");
    }

    @Test
    void surrounding_whitespace_in_the_header_is_tolerated() {
      ContributionFile file =
          parse(
              " participantInternalRef , participantPayrollRef , employmentStartDate ,"
                  + " enrolmentDate , employeeCents , employerCents , voluntaryCents \n"
                  + "P-001,ACME-EMP-0142,2019-03-01,2026-01-15,300000,150000,0\n");

      assertThat(file.rows()).hasSize(1);
    }

    @Test
    void a_file_with_only_a_column_header_is_malformed() {
      assertMalformed(ContributionFiles.columnHeader() + "\n");
    }

    @Test
    void an_empty_file_is_malformed() {
      assertMalformed("");
    }
  }

  @Nested
  class EmploymentDates {

    @Test
    void the_two_dates_are_kept_apart() {
      // The whole reason the dates are in the file: P-001 joined Acme in 2019 and the plan
      // in 2026. A parser that collapsed the two — or read them in the wrong order — would
      // put seven years of service on the wrong side of a vesting schedule.
      ContributionFile.ParticipantRow longService =
          parse(ContributionFiles.workedExample()).rows().get(0);

      assertThat(longService.employmentStartDate())
          .isEqualTo(ContributionFiles.LONG_SERVICE_EMPLOYMENT_START);
      assertThat(longService.enrolmentDate()).isEqualTo(ContributionFiles.LONG_SERVICE_ENROLMENT);
      assertThat(longService.employmentStartDate()).isBefore(longService.enrolmentDate());
    }

    @Test
    void the_dates_sit_on_the_same_row_as_the_employer_money_they_govern() {
      // §3: a vesting computation reads one row and joins nothing.
      ContributionFile.ParticipantRow row = parse(ContributionFiles.workedExample()).rows().get(0);

      assertThat(row.employmentStartDate()).isNotNull();
      assertThat(row.amountOf(ContributionSource.EMPLOYER)).isEqualTo(150_000L);
    }

    @Test
    void a_participant_enrolled_on_their_first_day_is_accepted() {
      // Equal dates are the common case, not a boundary to be refused: §3 requires only
      // that enrolment is not EARLIER than employment.
      ContributionFile.ParticipantRow sameDay =
          parse(ContributionFiles.workedExample()).rows().get(1);

      assertThat(sameDay.employmentStartDate()).isEqualTo(sameDay.enrolmentDate());
    }

    @Test
    void a_participant_enrolled_before_being_employed_is_malformed() {
      // §4 check 2: answerable from one row, so it belongs with the parser rather than
      // with the checks that compare the file against the submission.
      assertMalformed(oneRow("P-1,PAY-1,2026-03-01,2026-02-01,100,0,0"));
    }

    @Test
    void an_unparseable_employment_start_date_is_malformed() {
      assertMalformed(oneRow("P-1,PAY-1,01/03/2019,2026-01-15,100,0,0"));
    }

    @Test
    void an_unparseable_enrolment_date_is_malformed() {
      assertMalformed(oneRow("P-1,PAY-1,2019-03-01,not-a-date,100,0,0"));
    }
  }

  @Nested
  class Amounts {

    @Test
    void a_non_integer_amount_is_malformed() {
      assertMalformed(oneRow("P-1,PAY-1,2019-03-01,2026-01-15,100.00,0,0"));
    }

    @Test
    void a_decimal_amount_is_malformed_because_cents_are_integers() {
      // docs/ENGINEERING-RULES.md §4: no decimal point anywhere in a monetary value. A producer
      // that
      // writes pesos-with-decimals must be told so, not silently truncated.
      assertMalformed(oneRow("P-1,PAY-1,2019-03-01,2026-01-15,\"1,000\",0,0"));
    }

    @Test
    void a_negative_amount_is_malformed() {
      assertMalformed(oneRow("P-1,PAY-1,2019-03-01,2026-01-15,-100,0,0"));
    }

    @Test
    void a_row_contributing_nothing_from_any_source_is_malformed() {
      // §3: a participant contributing nothing this period simply has no row.
      assertMalformed(oneRow("P-1,PAY-1,2019-03-01,2026-01-15,0,0,0"));
    }

    @Test
    void a_single_zero_column_is_not_an_error() {
      // The other half of the rule above, and the one that matters: zero is how the format
      // says "nothing from this source", and it is on almost every real row.
      ContributionFile.ParticipantRow row =
          parse(oneRow("P-1,PAY-1,2019-03-01,2026-01-15,100,0,0")).rows().get(0);

      assertThat(row.amountOf(ContributionSource.EMPLOYEE)).isEqualTo(100L);
      assertThat(row.amountOf(ContributionSource.EMPLOYER)).isZero();
    }
  }

  @Nested
  class Columns {

    @Test
    void a_row_missing_a_column_is_malformed() {
      assertMalformed(oneRow("P-1,PAY-1,2019-03-01,2026-01-15,100,0"));
    }

    @Test
    void a_row_with_an_extra_column_is_malformed() {
      assertMalformed(oneRow("P-1,PAY-1,2019-03-01,2026-01-15,100,0,0,extra"));
    }

    @Test
    void a_blank_participant_reference_is_malformed() {
      assertMalformed(oneRow(",PAY-1,2019-03-01,2026-01-15,100,0,0"));
    }

    @Test
    void a_blank_payroll_reference_is_malformed() {
      assertMalformed(oneRow("P-1,,2019-03-01,2026-01-15,100,0,0"));
    }

    @Test
    void a_v1_file_is_malformed() {
      // v1's first line was an H record, not a column header, so it fails on line 1.
      assertMalformed(
          """
          H,1,ORG-ACME,PLAN-1,2026-08-B,2026-08-20,100,1
          S,EMPLOYEE,100
          D,P-1,PAY-1,EMPLOYEE,100
          """);
    }
  }

  @Nested
  class Framing {

    @Test
    void a_trailing_blank_line_is_tolerated() {
      assertThat(parse(ContributionFiles.workedExample() + "\n").rows()).hasSize(2);
    }

    @Test
    void carriage_returns_from_a_crlf_producer_are_tolerated() {
      String crlf = ContributionFiles.workedExample().replace("\n", "\r\n");

      assertThat(parse(crlf).rows()).hasSize(2);
    }

    @Test
    void a_second_column_header_line_is_malformed() {
      assertMalformed(ContributionFiles.workedExample() + ContributionFiles.columnHeader() + "\n");
    }
  }

  @Nested
  class Rfc4180Quoting {

    @Test
    void a_quoted_field_containing_a_comma_stays_one_field() {
      ContributionFile.ParticipantRow row =
          parse(oneRow("\"P-1, THE FIRST\",PAY-1,2019-03-01,2026-01-15,100,0,0")).rows().get(0);

      assertThat(row.participantInternalRef()).isEqualTo("P-1, THE FIRST");
    }

    @Test
    void a_doubled_quote_inside_a_quoted_field_is_a_literal_quote() {
      ContributionFile.ParticipantRow row =
          parse(oneRow("\"P \"\"1\"\"\",PAY-1,2019-03-01,2026-01-15,100,0,0")).rows().get(0);

      assertThat(row.participantInternalRef()).isEqualTo("P \"1\"");
    }

    @Test
    void an_unterminated_quoted_field_is_malformed() {
      assertMalformed(oneRow("\"P-1,PAY-1,2019-03-01,2026-01-15,100,0,0"));
    }
  }

  @Nested
  class Encoding {

    @Test
    void a_byte_order_mark_is_malformed() {
      // §1: UTF-8, no BOM. Tolerating one would make the first column of the header row
      // start with an invisible character that no comparison would ever match.
      assertMalformed("﻿" + ContributionFiles.workedExample());
    }

    @Test
    void bytes_that_are_not_valid_utf8_are_malformed() {
      // Lenient decoding would replace the bad byte with U+FFFD and carry on, so a
      // corrupted upload would parse into plausible-looking participant references.
      byte[] corrupted = {'p', 'a', 'r', 't', (byte) 0xC3, (byte) 0x28, '\n'};

      assertThatThrownBy(() -> ContributionFileParser.parse(corrupted))
          .isInstanceOf(BatchRejectedException.class)
          .extracting(e -> ((BatchRejectedException) e).rejection().code())
          .isEqualTo(BatchRejectionCode.MALFORMED_FILE);
    }
  }

  @Nested
  class NeverLogsAPerson {

    @Test
    void no_rejection_message_renders_a_reference() {
      // docs/ENGINEERING-RULES.md §7: a rejection body is logged by everything it passes through,
      // and a
      // participant reference identifies a person.
      assertThatThrownBy(
              () -> parse(oneRow("P-SECRET-0142,PAY-SECRET-9,2019-03-01,2026-01-15,nope,0,0")))
          .isInstanceOf(BatchRejectedException.class)
          .extracting(e -> ((BatchRejectedException) e).rejection().message())
          .asString()
          .doesNotContain("SECRET");
    }

    @Test
    void no_rejection_message_renders_an_employment_date() {
      // An employment date is data about a person too, and the check that compares them is
      // the one most tempted to quote them back.
      assertThatThrownBy(
              () -> parse(oneRow("P-SECRET-0142,PAY-SECRET-9,2026-03-01,2026-02-01,100,0,0")))
          .isInstanceOf(BatchRejectedException.class)
          .extracting(e -> ((BatchRejectedException) e).rejection().message())
          .asString()
          .doesNotContain("SECRET")
          .doesNotContain("2026-03-01")
          .doesNotContain("2026-02-01");
    }

    @Test
    void a_row_toString_renders_no_field_at_all() {
      // Every field of §3 is data about one identified employee — including the amounts —
      // and a record's toString reaches a log the moment anything interpolates it.
      ContributionFile.ParticipantRow row = parse(ContributionFiles.workedExample()).rows().get(0);

      assertThat(row.toString())
          .doesNotContain("P-001")
          .doesNotContain("ACME-EMP-0142")
          .doesNotContain("2019")
          .doesNotContain("300000");
    }
  }
}

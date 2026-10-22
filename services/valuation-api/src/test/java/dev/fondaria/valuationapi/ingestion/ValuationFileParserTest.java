package dev.fondaria.valuationapi.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@code valuation-file-v1.md} §1–§3, and checks 2 and 3 of §4. */
class ValuationFileParserTest {

  private static final String FUND = "7b4f1e2a-0c3d-4a5b-9e6f-1a2b3c4d5e6f";

  @Test
  @DisplayName("a well-formed file parses to its rows, amounts intact")
  void parsesAWellFormedFile() {
    ValuationFile file =
        parse(
            header()
                + FUND
                + ",2026-08-20,105000000,ACME-001\n"
                + "9d8c7b6a-5e4f-4321-8765-0fedcba98765,2026-08-20,48250075,ACME-002\n");

    assertThat(file.rows()).hasSize(2);
    ValuationFile.ValuationRow first = file.rows().get(0);
    assertThat(first.fundId().value()).isEqualTo(UUID.fromString(FUND));
    assertThat(first.valuationDate()).isEqualTo(LocalDate.of(2026, 8, 20));
    assertThat(first.reportedValueMinorUnits()).isEqualTo(105_000_000L);
    assertThat(first.providerReference()).isEqualTo("ACME-001");
    // The worked example's second fund: MXN 482,500.75, carried to the cent.
    assertThat(file.rows().get(1).reportedValueMinorUnits()).isEqualTo(48_250_075L);
  }

  /**
   * Task 20.3 acceptance criterion 4, and the reason {@code DECIMAL_AMOUNT} is its own code: a
   * reader that rounded {@code 1234.56} would be right almost every time and catastrophically wrong
   * the rest, on a number that reprices a whole fund.
   */
  @ParameterizedTest
  @ValueSource(strings = {"1234.56", "1234.0", "0.01", "105000000.00"})
  @DisplayName("a decimal reported value is rejected, never rounded")
  void rejectsDecimalAmountsWithoutRounding(String amount) {
    assertThatThrownBy(() -> parse(header() + FUND + ",2026-08-20," + amount + ",ACME-001\n"))
        .isInstanceOf(ValuationRejectedException.class)
        .extracting(e -> ((ValuationRejectedException) e).rejection().code())
        .isEqualTo(ValuationRejectionCode.DECIMAL_AMOUNT);
  }

  @Test
  @DisplayName("a quoted thousands separator is a decimal amount, not a parse accident")
  void rejectsAQuotedThousandsSeparator() {
    // Quoted, because an unquoted comma is a field separator and the file would fail the
    // column count first — a different fault with a different answer (below).
    assertThat(rejectionCodeOf(header() + FUND + ",2026-08-20,\"1,234\",ACME-001\n"))
        .isEqualTo(ValuationRejectionCode.DECIMAL_AMOUNT);
  }

  @Test
  @DisplayName("an unquoted thousands separator shifts the columns and is malformed")
  void rejectsAnUnquotedThousandsSeparatorAsMalformed() {
    // 1,234 unquoted is two CSV fields, so the row has five columns rather than four.
    // Reporting that as a column-count fault is correct: the file's framing is wrong, and
    // telling the producer "your amount has a decimal point" would point at the wrong fix.
    assertThat(rejectionCodeOf(header() + FUND + ",2026-08-20,1,234,ACME-001\n"))
        .isEqualTo(ValuationRejectionCode.MALFORMED_FILE);
  }

  @Test
  @DisplayName("a negative reported value is refused rather than floored at zero")
  void refusesANegativeReportedValue() {
    // Note what this is NOT: a clamp. Nothing floors the value — the file is refused. A
    // NAV struck from an accepted value is free to fall (docs/ENGINEERING-RULES.md §4).
    assertThat(rejectionCodeOf(header() + FUND + ",2026-08-20,-1,ACME-001\n"))
        .isEqualTo(ValuationRejectionCode.MALFORMED_FILE);
  }

  @Test
  @DisplayName("a zero reported value is accepted — a fund may genuinely be worth nothing")
  void acceptsAZeroReportedValue() {
    ValuationFile file = parse(header() + FUND + ",2026-08-20,0,ACME-001\n");
    assertThat(file.rows().get(0).reportedValueMinorUnits()).isZero();
  }

  @Test
  @DisplayName("a file whose first line is not exactly the column header is malformed")
  void requiresTheExactColumnHeader() {
    String shuffled = "valuationDate,fundId,reportedValueCents,providerReference\n";
    assertThat(rejectionCodeOf(shuffled + "2026-08-20," + FUND + ",1,ACME-001\n"))
        .isEqualTo(ValuationRejectionCode.MALFORMED_FILE);
  }

  @Test
  @DisplayName("a header with no rows is malformed")
  void requiresAtLeastOneRow() {
    assertThat(rejectionCodeOf(header())).isEqualTo(ValuationRejectionCode.MALFORMED_FILE);
  }

  @Test
  @DisplayName("a row with the wrong column count is malformed")
  void requiresEveryColumn() {
    assertThat(rejectionCodeOf(header() + FUND + ",2026-08-20,100\n"))
        .isEqualTo(ValuationRejectionCode.MALFORMED_FILE);
  }

  @Test
  @DisplayName("a non-UUID fund id is malformed")
  void requiresAUuidFundId() {
    assertThat(rejectionCodeOf(header() + "not-a-uuid,2026-08-20,100,ACME-001\n"))
        .isEqualTo(ValuationRejectionCode.MALFORMED_FILE);
  }

  @Test
  @DisplayName("a non-ISO valuation date is malformed")
  void requiresAnIsoDate() {
    assertThat(rejectionCodeOf(header() + FUND + ",20/08/2026,100,ACME-001\n"))
        .isEqualTo(ValuationRejectionCode.MALFORMED_FILE);
  }

  @Test
  @DisplayName("a blank provider reference is malformed")
  void requiresAProviderReference() {
    assertThat(rejectionCodeOf(header() + FUND + ",2026-08-20,100,\n"))
        .isEqualTo(ValuationRejectionCode.MALFORMED_FILE);
  }

  @Test
  @DisplayName("a byte-order mark is refused rather than silently consumed")
  void refusesAByteOrderMark() {
    String withBom = "﻿" + header() + FUND + ",2026-08-20,100,ACME-001\n";
    assertThat(rejectionCodeOf(withBom)).isEqualTo(ValuationRejectionCode.MALFORMED_FILE);
  }

  @Test
  @DisplayName("undecodable bytes are refused rather than replaced with U+FFFD")
  void refusesUndecodableBytes() {
    byte[] valid = (header() + FUND + ",2026-08-20,100,ACME-").getBytes(StandardCharsets.UTF_8);
    byte[] bytes = new byte[valid.length + 2];
    System.arraycopy(valid, 0, bytes, 0, valid.length);
    // A lone continuation byte: not valid UTF-8 in any position.
    bytes[valid.length] = (byte) 0x80;
    bytes[valid.length + 1] = '\n';

    assertThatThrownBy(() -> ValuationFileParser.parse(bytes))
        .isInstanceOf(ValuationRejectedException.class)
        .extracting(e -> ((ValuationRejectedException) e).rejection().code())
        .isEqualTo(ValuationRejectionCode.MALFORMED_FILE);
  }

  @Test
  @DisplayName("a trailing newline and CRLF line endings are tolerated")
  void toleratesTrailingNewlinesAndCarriageReturns() {
    ValuationFile file =
        parse(header().stripTrailing() + "\r\n" + FUND + ",2026-08-20,100,ACME-001\r\n\n");
    assertThat(file.rows()).hasSize(1);
    assertThat(file.rows().get(0).providerReference()).isEqualTo("ACME-001");
  }

  @Test
  @DisplayName("an RFC 4180 quoted field containing a comma is read as one field")
  void readsQuotedFields() {
    ValuationFile file = parse(header() + FUND + ",2026-08-20,100,\"ACME, division 2\"\n");
    assertThat(file.rows().get(0).providerReference()).isEqualTo("ACME, division 2");
  }

  private static String header() {
    return ValuationFileParser.columnHeaderLine() + "\n";
  }

  private static ValuationFile parse(String text) {
    return ValuationFileParser.parse(text.getBytes(StandardCharsets.UTF_8));
  }

  private static ValuationRejectionCode rejectionCodeOf(String text) {
    try {
      parse(text);
      throw new AssertionError("Expected the file to be rejected");
    } catch (ValuationRejectedException e) {
      return e.rejection().code();
    }
  }
}

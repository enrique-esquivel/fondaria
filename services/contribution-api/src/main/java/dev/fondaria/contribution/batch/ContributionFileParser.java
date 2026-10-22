package dev.fondaria.contribution.batch;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses the bytes of a contribution file into a {@link ContributionFile} — {@code
 * contribution-file-v2.md} §1–§3.
 *
 * <p>Structure only, and it stops at the first structural problem with {@code MALFORMED_FILE}
 * (check 2 of §4). It compares nothing against the batch's declarations: the row count, the
 * per-source column sums and the grand total are checks 3, 5 and 6, they have their own codes, and
 * running them here would collapse three distinct answers to the employer into one unhelpful one.
 * The two comparisons it does make — a row that contributes nothing, and dates the wrong way round
 * — are answerable from a single row.
 *
 * <p>Pure: bytes in, rows out, no I/O and no clock. That is what lets the whole rejection contract
 * be proved without a container.
 */
public final class ContributionFileParser {

  private static final char DELIMITER = ',';
  private static final char QUOTE = '"';

  /** The four columns of §3 that describe the participant, before the amounts. */
  private static final List<String> IDENTITY_COLUMNS =
      List.of(
          "participantInternalRef",
          "participantPayrollRef",
          "employmentStartDate",
          "enrolmentDate");

  /**
   * The amount columns of §3, in order, and the <b>one</b> place the file's source vocabulary is
   * written down. Adding a source is this list, one {@link ContributionSource} constant, one column
   * in the contract and a new format version — the bounded cost §1.2 accepts in exchange for the
   * file being a rectangle.
   */
  private static final List<ContributionSource> SOURCE_COLUMNS =
      List.of(
          ContributionSource.EMPLOYEE, ContributionSource.EMPLOYER, ContributionSource.VOLUNTARY);

  /** §2's column-header row, derived from the two lists above so it can never drift from them. */
  private static final List<String> COLUMN_HEADER = columnHeader();

  private static final int COLUMNS = COLUMN_HEADER.size();

  private ContributionFileParser() {}

  /** The exact first line a producer must emit, for the contract and for a fixture to reuse. */
  public static String columnHeaderLine() {
    return String.join(String.valueOf(DELIMITER), COLUMN_HEADER);
  }

  /**
   * @throws BatchRejectedException {@code MALFORMED_FILE} — the file is not decodable UTF-8, does
   *     not start with the column-header row, has a missing or ill-typed column, carries a
   *     participant who contributed nothing, or declares a participant enrolled before they were
   *     employed
   */
  public static ContributionFile parse(byte[] bytes) {
    List<String> lines = splitLines(decode(bytes));
    if (lines.isEmpty()) {
      throw malformed("The file is empty.");
    }
    requireColumnHeader(splitFields(lines.get(0), 1));

    List<ContributionFile.ParticipantRow> rows = new ArrayList<>();
    for (int i = 1; i < lines.size(); i++) {
      int lineNumber = i + 1;
      rows.add(parseRow(splitFields(lines.get(i), lineNumber), lineNumber));
    }
    if (rows.isEmpty()) {
      // §1: one or more participant rows. A file with nothing to contribute is not a batch.
      throw malformed("The file has a column header and no participant rows.");
    }
    return new ContributionFile(rows);
  }

  /**
   * §2 — the first line is exactly the seven column names, in order.
   *
   * <p>Required rather than optional, and matched exactly rather than leniently. A producer that
   * shifts a column would otherwise be read as valid, and an employer amount landing in the
   * voluntary column is a silent misallocation rather than a parse error.
   */
  private static void requireColumnHeader(List<String> fields) {
    List<String> actual = fields.stream().map(String::trim).toList();
    if (!actual.equals(COLUMN_HEADER)) {
      throw malformed(
          "The first line of the file is not the expected column header. It must be exactly: "
              + columnHeaderLine());
    }
  }

  /**
   * One participant row (§3), including the two checks answerable from the row alone.
   *
   * <p>They belong here rather than in {@link ContributionBatchValidator} because the validator
   * compares the file against the batch's declarations and these need neither. The date comparison
   * matters most: those two dates are what a graded vesting schedule is computed from, so a file
   * that has them the wrong way round would vest employer money against a service period that never
   * happened.
   */
  private static ContributionFile.ParticipantRow parseRow(List<String> fields, int lineNumber) {
    requireColumnCount(fields, lineNumber);
    LocalDate employmentStart = parseDate(fields.get(2), "employmentStartDate", lineNumber);
    LocalDate enrolment = parseDate(fields.get(3), "enrolmentDate", lineNumber);
    if (employmentStart.isAfter(enrolment)) {
      // Neither date is echoed: an employment date is data about a person (AGENTS.md §7).
      throw malformed(
          "The participant at line "
              + lineNumber
              + " is enrolled in the plan before being employed by the organization.");
    }

    Map<ContributionSource, Long> amounts = new EnumMap<>(ContributionSource.class);
    long total = 0L;
    for (int i = 0; i < SOURCE_COLUMNS.size(); i++) {
      ContributionSource source = SOURCE_COLUMNS.get(i);
      long amount =
          parseNonNegativeLong(
              fields.get(IDENTITY_COLUMNS.size() + i), amountColumnName(source), lineNumber);
      amounts.put(source, amount);
      total = Math.addExact(total, amount);
    }
    if (total == 0) {
      // §3: a participant contributing nothing this period simply has no row. A single zero
      // column is not an error — that is how the format says "nothing from this source".
      throw malformed(
          "The participant at line " + lineNumber + " contributes nothing from any source.");
    }

    return new ContributionFile.ParticipantRow(
        requireNonBlank(fields.get(0), IDENTITY_COLUMNS.get(0), lineNumber),
        requireNonBlank(fields.get(1), IDENTITY_COLUMNS.get(1), lineNumber),
        employmentStart,
        enrolment,
        amounts);
  }

  private static List<String> columnHeader() {
    List<String> names = new ArrayList<>(IDENTITY_COLUMNS);
    for (ContributionSource source : SOURCE_COLUMNS) {
      names.add(amountColumnName(source));
    }
    return List.copyOf(names);
  }

  /** {@code EMPLOYEE} → {@code employeeCents}. */
  private static String amountColumnName(ContributionSource source) {
    return source.name().toLowerCase(Locale.ROOT) + "Cents";
  }

  /**
   * UTF-8, strictly, with no byte-order mark (§1). Lenient decoding would replace an undecodable
   * byte with U+FFFD and carry on, so a corrupted upload would parse into plausible-looking
   * participant references instead of being rejected.
   */
  private static String decode(byte[] bytes) {
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      if (!text.isEmpty() && text.charAt(0) == '﻿') {
        throw malformed("The file starts with a byte-order mark; UTF-8 without a BOM is required.");
      }
      return text;
    } catch (CharacterCodingException e) {
      throw malformed("The file is not valid UTF-8.");
    }
  }

  /**
   * Splits on {@code \n} (§1) and drops a trailing empty line, which every text editor and most
   * producers add. A bare {@code \r} left by a CRLF producer is trimmed per field rather than
   * rejected outright: a stray carriage return is not a reason to refuse an employer's payroll.
   */
  private static List<String> splitLines(String text) {
    List<String> lines = new ArrayList<>();
    for (String line : text.split("\n", -1)) {
      String trimmed = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
      if (!trimmed.isEmpty()) {
        lines.add(trimmed);
      }
    }
    return lines;
  }

  /**
   * RFC 4180 field splitting: a quoted field may contain the delimiter, and a doubled quote inside
   * one is a literal quote. None of v2's fields need quoting, but §1 requires a producer that emits
   * quotes to be read correctly rather than split naively on every comma.
   *
   * <p>A field spanning a line break is not supported and is malformed. Every v2 field is a scalar
   * — an id, a date, an integer — so a newline inside one is corruption, and accepting it would let
   * a truncated file silently re-frame the rows after it.
   */
  private static List<String> splitFields(String line, int lineNumber) {
    List<String> fields = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quoted) {
        if (c == QUOTE) {
          if (i + 1 < line.length() && line.charAt(i + 1) == QUOTE) {
            field.append(QUOTE);
            i++;
          } else {
            quoted = false;
          }
        } else {
          field.append(c);
        }
      } else if (c == QUOTE) {
        if (!field.isEmpty()) {
          throw malformed("A quote appears mid-field at line " + lineNumber + ".");
        }
        quoted = true;
      } else if (c == DELIMITER) {
        fields.add(field.toString());
        field.setLength(0);
      } else {
        field.append(c);
      }
    }
    if (quoted) {
      throw malformed("An unterminated quoted field at line " + lineNumber + ".");
    }
    fields.add(field.toString());
    return fields;
  }

  private static void requireColumnCount(List<String> fields, int lineNumber) {
    if (fields.size() != COLUMNS) {
      throw malformed(
          "Line "
              + lineNumber
              + " has "
              + fields.size()
              + " columns; a participant row has "
              + COLUMNS
              + ".");
    }
  }

  private static String requireNonBlank(String value, String column, int lineNumber) {
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw malformed("Column " + column + " at line " + lineNumber + " is blank.");
    }
    return trimmed;
  }

  private static LocalDate parseDate(String value, String column, int lineNumber) {
    try {
      return LocalDate.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw malformed("Column " + column + " at line " + lineNumber + " is not a YYYY-MM-DD date.");
    }
  }

  /**
   * {@code long}, never {@code BigDecimal} or {@code double}, and never a decimal point: §3 makes
   * every amount an integer of cents with no separator and no currency symbol (AGENTS.md §4).
   */
  private static long parseNonNegativeLong(String value, String column, int lineNumber) {
    long parsed;
    try {
      parsed = Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      throw malformed("Column " + column + " at line " + lineNumber + " is not an integer.");
    }
    if (parsed < 0) {
      throw malformed("Column " + column + " at line " + lineNumber + " is negative.");
    }
    return parsed;
  }

  private static BatchRejectedException malformed(String message) {
    return new BatchRejectedException(
        BatchRejection.of(BatchRejectionCode.MALFORMED_FILE, message));
  }
}

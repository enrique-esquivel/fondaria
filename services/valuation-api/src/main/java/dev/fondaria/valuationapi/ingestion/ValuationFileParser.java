package dev.fondaria.valuationapi.ingestion;

import dev.fondaria.common.FundId;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Parses the bytes of a valuation file into a {@link ValuationFile} — {@code valuation-file-v1.md}
 * §1–§3.
 *
 * <p>Structure only, and it stops at the first structural problem (checks 2 and 3 of §4). It
 * compares nothing against the batch's declarations, the fund registry, the calendar or what has
 * already been accepted: those are checks 4 to 9, they have their own codes, and running them here
 * would collapse several distinct answers to the provider into one unhelpful one.
 *
 * <p>Pure: bytes in, rows out, no I/O and no clock. That is what lets the parse half of the
 * rejection contract be proved without a container.
 */
public final class ValuationFileParser {

  private static final char DELIMITER = ',';
  private static final char QUOTE = '"';

  /** §2's column-header row, in order. */
  private static final List<String> COLUMN_HEADER =
      List.of("fundId", "valuationDate", "reportedValueCents", "providerReference");

  private static final int COLUMNS = COLUMN_HEADER.size();

  private ValuationFileParser() {}

  /** The exact first line a producer must emit, for the contract and for a fixture to reuse. */
  public static String columnHeaderLine() {
    return String.join(String.valueOf(DELIMITER), COLUMN_HEADER);
  }

  /**
   * @throws ValuationRejectedException {@code MALFORMED_FILE} for a structural fault, or {@code
   *     DECIMAL_AMOUNT} for a reported value carrying a decimal point
   */
  public static ValuationFile parse(byte[] bytes) {
    List<String> lines = splitLines(decode(bytes));
    if (lines.isEmpty()) {
      throw malformed("The file is empty.");
    }
    requireColumnHeader(splitFields(lines.get(0), 1));

    List<ValuationFile.ValuationRow> rows = new ArrayList<>();
    for (int i = 1; i < lines.size(); i++) {
      int lineNumber = i + 1;
      rows.add(parseRow(splitFields(lines.get(i), lineNumber), lineNumber));
    }
    if (rows.isEmpty()) {
      throw malformed("The file has a column header and no valuation rows.");
    }
    return new ValuationFile(rows);
  }

  /**
   * §2 — the first line is exactly the four column names, in order.
   *
   * <p>Matched exactly rather than leniently, for the reason the contribution parser matches its
   * own header exactly: a producer that shifts a column would otherwise be read as valid, and a
   * reported balance landing in the wrong column moves every participant's balance in that fund.
   */
  private static void requireColumnHeader(List<String> fields) {
    List<String> actual = fields.stream().map(String::trim).toList();
    if (!actual.equals(COLUMN_HEADER)) {
      throw malformed(
          "The first line of the file is not the expected column header. It must be exactly: "
              + columnHeaderLine());
    }
  }

  private static ValuationFile.ValuationRow parseRow(List<String> fields, int lineNumber) {
    requireColumnCount(fields, lineNumber);
    return new ValuationFile.ValuationRow(
        parseFundId(fields.get(0), lineNumber),
        parseDate(fields.get(1), lineNumber),
        parseReportedValue(fields.get(2), lineNumber),
        requireNonBlank(fields.get(3), "providerReference", lineNumber));
  }

  private static FundId parseFundId(String value, int lineNumber) {
    try {
      return new FundId(UUID.fromString(value.trim()));
    } catch (IllegalArgumentException e) {
      throw malformed("Column fundId at line " + lineNumber + " is not a UUID.");
    }
  }

  private static LocalDate parseDate(String value, int lineNumber) {
    try {
      return LocalDate.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw malformed("Column valuationDate at line " + lineNumber + " is not a YYYY-MM-DD date.");
    }
  }

  /**
   * Check 3 of §4: {@code long}, never a fixed-point or fractional type, and <b>never a decimal
   * point</b> (docs/ENGINEERING-RULES.md §4).
   *
   * <p>A decimal gets its own rejection code rather than {@code MALFORMED_FILE}. It is the one
   * parse failure whose tempting recovery is wrong: {@code 1234.56} plainly means MXN 1,234.56, and
   * a reader that rounds it is right almost every time and catastrophically wrong the rest — on a
   * number that reprices a whole fund. Detected before {@link Long#parseLong} rather than after,
   * because a caller reading only the code should not have to infer which kind of unparseable this
   * was.
   */
  private static long parseReportedValue(String value, int lineNumber) {
    String trimmed = value.trim();
    if (trimmed.indexOf('.') >= 0 || trimmed.indexOf(',') >= 0) {
      throw new ValuationRejectedException(
          ValuationRejection.of(
              ValuationRejectionCode.DECIMAL_AMOUNT,
              "Column reportedValueCents at line "
                  + lineNumber
                  + " carries a decimal point or separator. It is an integer number of MXN cents:"
                  + " MXN 1,234.56 is written 123456. The value is refused rather than rounded.",
              Map.of("line", lineNumber)));
    }
    long parsed;
    try {
      parsed = Long.parseLong(trimmed);
    } catch (NumberFormatException e) {
      throw malformed("Column reportedValueCents at line " + lineNumber + " is not an integer.");
    }
    if (parsed < 0) {
      // A pooled balance is not negative. Note this is NOT a clamp: nothing here floors a
      // value at zero, it refuses the file. The NAV struck from an accepted value is free
      // to fall, and must be (docs/ENGINEERING-RULES.md §4).
      throw malformed("Column reportedValueCents at line " + lineNumber + " is negative.");
    }
    return parsed;
  }

  /**
   * UTF-8, strictly, with no byte-order mark (§1). Lenient decoding would replace an undecodable
   * byte with U+FFFD and carry on, so a corrupted upload would parse into plausible-looking values
   * instead of being rejected.
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
   * producers add. A bare {@code \r} left by a CRLF producer is trimmed per line rather than
   * rejected outright.
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
   * one is a literal quote. None of v1's fields need quoting, but §1 requires a producer that emits
   * quotes to be read correctly rather than split naively on every comma.
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
              + " columns; a valuation row has "
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

  private static ValuationRejectedException malformed(String message) {
    return new ValuationRejectedException(
        ValuationRejection.of(ValuationRejectionCode.MALFORMED_FILE, message));
  }
}

package dev.fondaria.e2e;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the JSON number tokens in a raw response body, so a test can assert that none of them is a
 * float.
 *
 * <p><b>Why a scanner and not a regex.</b> Every date, timestamp and identifier in a Query API
 * response is a JSON string, and several of them contain digits, dots and dashes — {@code
 * "2026-09-03"}, {@code "0.1"} in a problem-detail message, a fund's UUID. A regex over the whole
 * body cannot tell a decimal literal from the inside of a quoted string, and one that tried would
 * either miss floats or fail on dates. This walks the body once, tracks whether it is inside a
 * string (honouring escapes), and reports only what JSON itself would parse as a number.
 */
final class JsonNumbers {

  private JsonNumbers() {}

  /** Every number token in {@code body}, in the order the bytes carry them. */
  static List<String> numbersIn(String body) {
    List<String> numbers = new ArrayList<>();
    boolean inString = false;
    boolean escaped = false;
    int index = 0;
    while (index < body.length()) {
      char current = body.charAt(index);
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (current == '\\') {
          escaped = true;
        } else if (current == '"') {
          inString = false;
        }
        index++;
        continue;
      }
      if (current == '"') {
        inString = true;
        index++;
        continue;
      }
      if (startsNumber(body, index)) {
        int end = index;
        while (end < body.length() && isNumberCharacter(body.charAt(end))) {
          end++;
        }
        numbers.add(body.substring(index, end));
        index = end;
        continue;
      }
      index++;
    }
    return numbers;
  }

  /**
   * The number tokens that are not integers — a fractional part or an exponent. This is what
   * AGENTS.md §4 forbids on the wire for cash, units, NAV, a rate or a return.
   */
  static List<String> decimalLiteralsIn(String body) {
    return numbersIn(body).stream().filter(number -> !number.matches("-?\\d+")).toList();
  }

  private static boolean startsNumber(String body, int index) {
    char current = body.charAt(index);
    if (Character.isDigit(current)) {
      // Not the tail of a token that is not a number: outside a string, a digit can only follow
      // structural JSON or another number character, both of which this scanner has consumed.
      return true;
    }
    return current == '-' && index + 1 < body.length() && Character.isDigit(body.charAt(index + 1));
  }

  private static boolean isNumberCharacter(char character) {
    return Character.isDigit(character)
        || character == '-'
        || character == '+'
        || character == '.'
        || character == 'e'
        || character == 'E';
  }
}

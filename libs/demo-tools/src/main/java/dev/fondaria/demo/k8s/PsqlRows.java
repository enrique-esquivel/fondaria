package dev.fondaria.demo.k8s;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splits {@code psql -A -t -F <unit separator>} output into rows of columns. A {@code NULL} column
 * arrives as an empty string, which is how every caller in this module treats an absent value.
 */
public final class PsqlRows {

  /** ASCII unit separator: never inside a date, a uuid, a count, a status or an object key. */
  public static final String FIELD_SEPARATOR = "\u001f";

  private PsqlRows() {}

  public static List<List<String>> parse(String output) {
    List<List<String>> rows = new ArrayList<>();
    for (String line : output.split("\n", -1)) {
      if (line.isBlank()) {
        continue;
      }
      rows.add(List.of(line.split(FIELD_SEPARATOR, -1)));
    }
    return rows;
  }

  /** The single row a query keyed on a primary key returns, or empty if there is none. */
  public static java.util.Optional<List<String>> single(List<List<String>> rows) {
    if (rows.size() > 1) {
      throw new IllegalStateException(
          "expected at most one row, got " + rows.size() + ": " + Arrays.toString(rows.toArray()));
    }
    return rows.stream().findFirst();
  }
}

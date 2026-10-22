package dev.fondaria.demo.json;

import java.util.List;
import java.util.Map;

/**
 * Writes a JSON value from plain JDK types, so this module needs no JSON library (`phase-D-demo.md`
 * Task D.2: "no framework"; every payload here is small and shaped by this module itself, never
 * parsed from free text).
 *
 * <p>Accepts {@link Map} (rendered as an object, insertion order preserved -- callers pass a {@link
 * java.util.LinkedHashMap}), {@link List} (an array), {@link String}, {@link Number}, {@link
 * Boolean}, and {@code null}. Anything else is a programmer error, not a data problem, so it throws
 * rather than best-effort stringifying it.
 */
public final class Json {

  private Json() {}

  public static String write(Object value) {
    StringBuilder out = new StringBuilder();
    writeValue(value, out);
    return out.toString();
  }

  private static void writeValue(Object value, StringBuilder out) {
    switch (value) {
      case null -> out.append("null");
      case String s -> writeString(s, out);
      case Boolean b -> out.append(b.toString());
      case Number n -> out.append(n.toString());
      case Map<?, ?> map -> writeObject(map, out);
      case List<?> list -> writeArray(list, out);
      default ->
          throw new IllegalArgumentException(
              "Json.write cannot render a " + value.getClass().getName());
    }
  }

  private static void writeObject(Map<?, ?> map, StringBuilder out) {
    out.append('{');
    boolean first = true;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!first) {
        out.append(',');
      }
      first = false;
      writeString(String.valueOf(entry.getKey()), out);
      out.append(':');
      writeValue(entry.getValue(), out);
    }
    out.append('}');
  }

  private static void writeArray(List<?> list, StringBuilder out) {
    out.append('[');
    boolean first = true;
    for (Object element : list) {
      if (!first) {
        out.append(',');
      }
      first = false;
      writeValue(element, out);
    }
    out.append(']');
  }

  private static void writeString(String s, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}

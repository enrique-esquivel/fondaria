package dev.fondaria.demo.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a JSON value into plain JDK types, the counterpart to {@link Json}. Used for the platform's
 * own HTTP responses and this module's own state files -- never for arbitrary or adversarial input,
 * so a malformed document throws rather than recovering partial data.
 *
 * <p>An object becomes a {@link LinkedHashMap} (insertion order preserved), an array a {@link
 * List}, a number a {@link Long} when it parses as one with no loss, otherwise a {@link Double}.
 */
public final class JsonParser {

  private final String source;
  private int position;

  private JsonParser(String source) {
    this.source = source;
  }

  public static Object parse(String json) {
    JsonParser parser = new JsonParser(json);
    parser.skipWhitespace();
    Object value = parser.parseValue();
    parser.skipWhitespace();
    if (parser.position != json.length()) {
      throw new IllegalArgumentException("trailing content after JSON value at " + parser.position);
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseObject(String json) {
    Object value = parse(json);
    if (!(value instanceof Map)) {
      throw new IllegalArgumentException("expected a JSON object, got: " + value);
    }
    return (Map<String, Object>) value;
  }

  /** Dotted-path lookup, e.g. {@code "parts.allottedParts"}. Throws if any segment is absent. */
  public static Object at(Map<String, Object> object, String dottedPath) {
    Object current = object;
    for (String segment : dottedPath.split("\\.")) {
      if (!(current instanceof Map)) {
        throw new IllegalArgumentException(
            "path segment '" + segment + "' of '" + dottedPath + "' is not an object");
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> map = (Map<String, Object>) current;
      if (!map.containsKey(segment)) {
        throw new IllegalArgumentException(
            "missing field '" + segment + "' of '" + dottedPath + "' in " + object);
      }
      current = map.get(segment);
    }
    return current;
  }

  public static String str(Map<String, Object> object, String dottedPath) {
    Object value = at(object, dottedPath);
    if (value == null) {
      return null;
    }
    return String.valueOf(value);
  }

  public static long longAt(Map<String, Object> object, String dottedPath) {
    Object value = at(object, dottedPath);
    if (value instanceof Number n) {
      return n.longValue();
    }
    throw new IllegalArgumentException("field '" + dottedPath + "' is not a number: " + value);
  }

  private Object parseValue() {
    skipWhitespace();
    if (position >= source.length()) {
      throw new IllegalArgumentException("unexpected end of JSON input");
    }
    char c = source.charAt(position);
    return switch (c) {
      case '{' -> parseObjectValue();
      case '[' -> parseArrayValue();
      case '"' -> parseStringValue();
      case 't', 'f' -> parseBooleanValue();
      case 'n' -> parseNullValue();
      default -> parseNumberValue();
    };
  }

  private Map<String, Object> parseObjectValue() {
    Map<String, Object> result = new LinkedHashMap<>();
    expect('{');
    skipWhitespace();
    if (peek() == '}') {
      position++;
      return result;
    }
    while (true) {
      skipWhitespace();
      String key = parseStringValue();
      skipWhitespace();
      expect(':');
      Object value = parseValue();
      result.put(key, value);
      skipWhitespace();
      char next = source.charAt(position);
      if (next == ',') {
        position++;
      } else if (next == '}') {
        position++;
        break;
      } else {
        throw new IllegalArgumentException("expected ',' or '}' at " + position);
      }
    }
    return result;
  }

  private List<Object> parseArrayValue() {
    List<Object> result = new ArrayList<>();
    expect('[');
    skipWhitespace();
    if (peek() == ']') {
      position++;
      return result;
    }
    while (true) {
      result.add(parseValue());
      skipWhitespace();
      char next = source.charAt(position);
      if (next == ',') {
        position++;
      } else if (next == ']') {
        position++;
        break;
      } else {
        throw new IllegalArgumentException("expected ',' or ']' at " + position);
      }
    }
    return result;
  }

  private String parseStringValue() {
    expect('"');
    StringBuilder result = new StringBuilder();
    while (true) {
      char c = source.charAt(position++);
      if (c == '"') {
        break;
      }
      if (c == '\\') {
        char escape = source.charAt(position++);
        switch (escape) {
          case '"' -> result.append('"');
          case '\\' -> result.append('\\');
          case '/' -> result.append('/');
          case 'n' -> result.append('\n');
          case 'r' -> result.append('\r');
          case 't' -> result.append('\t');
          case 'b' -> result.append('\b');
          case 'f' -> result.append('\f');
          case 'u' -> {
            String hex = source.substring(position, position + 4);
            position += 4;
            result.append((char) Integer.parseInt(hex, 16));
          }
          default -> throw new IllegalArgumentException("unknown escape \\" + escape);
        }
      } else {
        result.append(c);
      }
    }
    return result.toString();
  }

  private Boolean parseBooleanValue() {
    if (source.startsWith("true", position)) {
      position += 4;
      return Boolean.TRUE;
    }
    if (source.startsWith("false", position)) {
      position += 5;
      return Boolean.FALSE;
    }
    throw new IllegalArgumentException("expected boolean at " + position);
  }

  private Object parseNullValue() {
    if (source.startsWith("null", position)) {
      position += 4;
      return null;
    }
    throw new IllegalArgumentException("expected null at " + position);
  }

  private Number parseNumberValue() {
    int start = position;
    if (peek() == '-') {
      position++;
    }
    boolean isDecimal = false;
    while (position < source.length()) {
      char c = source.charAt(position);
      if (Character.isDigit(c)) {
        position++;
      } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
        isDecimal = isDecimal || (c == '.' || c == 'e' || c == 'E');
        position++;
      } else {
        break;
      }
    }
    String token = source.substring(start, position);
    if (token.isEmpty()) {
      throw new IllegalArgumentException("expected number at " + start);
    }
    if (isDecimal) {
      return Double.parseDouble(token);
    }
    try {
      return Long.parseLong(token);
    } catch (NumberFormatException e) {
      return Double.parseDouble(token);
    }
  }

  private char peek() {
    return source.charAt(position);
  }

  private void expect(char expected) {
    skipWhitespace();
    char actual = source.charAt(position);
    if (actual != expected) {
      throw new IllegalArgumentException(
          "expected '" + expected + "' but found '" + actual + "' at " + position);
    }
    position++;
  }

  private void skipWhitespace() {
    while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
      position++;
    }
  }
}

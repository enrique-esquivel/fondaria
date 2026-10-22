package dev.fondaria.demo;

import java.util.HashMap;
import java.util.Map;

/**
 * Parses {@code --flag value} pairs and bare {@code --flag} switches. No framework, per the task.
 */
public final class Args {

  private final Map<String, String> values = new HashMap<>();
  private final java.util.Set<String> switches = new java.util.HashSet<>();

  public static Args parse(String[] argv, int fromIndex) {
    Args args = new Args();
    int i = fromIndex;
    while (i < argv.length) {
      String token = argv[i];
      if (!token.startsWith("--")) {
        throw new DemoToolsException("unexpected argument: " + token);
      }
      String flag = token.substring(2);
      if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
        args.values.put(flag, argv[i + 1]);
        i += 2;
      } else {
        args.switches.add(flag);
        i += 1;
      }
    }
    return args;
  }

  public String get(String flag, String defaultValue) {
    return values.getOrDefault(flag, defaultValue);
  }

  public String require(String flag) {
    String value = values.get(flag);
    if (value == null) {
      throw new DemoToolsException("missing required argument: --" + flag);
    }
    return value;
  }

  public long getLong(String flag, long defaultValue) {
    String value = values.get(flag);
    return value == null ? defaultValue : Long.parseLong(value);
  }

  public int getInt(String flag, int defaultValue) {
    String value = values.get(flag);
    return value == null ? defaultValue : Integer.parseInt(value);
  }

  public boolean has(String flag) {
    return switches.contains(flag) || values.containsKey(flag);
  }
}

package ru.bccontrol.sedonastudio.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Interface texts by key. English is built into the application and is the fallback for any key the
 * active language lacks; other languages are files (see storage.Languages).
 */
public final class Tr {
  private static final Map<String, String> ENGLISH = builtIn();
  private static volatile Map<String, String> active = Map.of();

  private Tr() {}

  /**
   * The text for a key with {0}, {1}... replaced by the arguments. Unknown keys come back as is.
   */
  public static String of(String key, Object... args) {
    String pattern = active.get(key);
    if (pattern == null) pattern = ENGLISH.get(key);
    if (pattern == null) return key;
    return args == null || args.length == 0 ? pattern : fill(pattern, args);
  }

  /** Switch the active language; an empty map means built-in English. */
  public static void use(Map<String, String> texts) {
    active = Map.copyOf(texts);
  }

  public static boolean known(String key) {
    return ENGLISH.containsKey(key);
  }

  /** Reads a .properties text (UTF-8, the usual escapes). */
  public static Map<String, String> parse(Reader reader) throws IOException {
    Properties properties = new Properties();
    properties.load(reader);
    Map<String, String> result = new HashMap<>();
    for (String name : properties.stringPropertyNames())
      result.put(name, properties.getProperty(name));
    return result;
  }

  // Plain substitution on purpose: no number grouping, no quote rules, same result as "a" + value.
  private static String fill(String pattern, Object[] args) {
    StringBuilder out = new StringBuilder(pattern.length() + 16 * args.length);
    int at = 0;
    while (at < pattern.length()) {
      int open = pattern.indexOf('{', at);
      if (open < 0) break;
      int close = open + 1;
      while (close < pattern.length() && Character.isDigit(pattern.charAt(close))) close++;
      if (close == open + 1 || close >= pattern.length() || pattern.charAt(close) != '}') {
        out.append(pattern, at, open + 1);
        at = open + 1;
        continue;
      }
      int index = Integer.parseInt(pattern.substring(open + 1, close));
      out.append(pattern, at, open);
      if (index < args.length) out.append(String.valueOf(args[index]));
      else out.append(pattern, open, close + 1);
      at = close + 1;
    }
    return out.append(pattern, at, pattern.length()).toString();
  }

  private static Map<String, String> builtIn() {
    try (InputStream in = Tr.class.getResourceAsStream("/lang/en.properties")) {
      if (in == null) return Map.of();
      return Map.copyOf(parse(new InputStreamReader(in, StandardCharsets.UTF_8)));
    } catch (IOException e) {
      return Map.of();
    }
  }
}

package ru.bccontrol.panel.api;

import java.util.function.BinaryOperator;

/**
 * Texts a panel module shows. The module writes the English text at the call site; a host that has
 * translations installs a source that may answer with another language for the same key.
 */
public final class PanelText {
  private static volatile BinaryOperator<String> source = (key, english) -> english;

  private PanelText() {}

  /**
   * The text for a key ({0}, {1}... replaced by the arguments); English unless the host knows
   * better.
   */
  public static String of(String key, String english, Object... args) {
    String pattern;
    try {
      pattern = source.apply(key, english);
    } catch (RuntimeException e) {
      pattern = english;
    }
    if (pattern == null) pattern = english;
    if (args == null || args.length == 0) return pattern;
    String result = pattern;
    for (int i = 0; i < args.length; i++)
      result = result.replace("{" + i + "}", String.valueOf(args[i]));
    return result;
  }

  /** Called by the host once; (key, english) -> translated pattern or the English one. */
  public static void source(BinaryOperator<String> translations) {
    source = translations == null ? (key, english) -> english : translations;
  }
}

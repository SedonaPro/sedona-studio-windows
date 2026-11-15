package ru.bccontrol.sedonastudio.model;
/** Millisecond polling settings, including compatibility with the previous second-based key. */
public final class PollingInterval {
  public static final int MIN = 100, MAX = 60000, DEFAULT = 1000;

  public static int parse(String text) {
    try {
      int value = Integer.parseInt(text.strip());
      if (value < MIN || value > MAX) throw new NumberFormatException();
      return value;
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(Tr.of("polling.interval"));
    }
  }

  public static int restore(int milliseconds, int legacySeconds) {
    if (milliseconds >= MIN && milliseconds <= MAX) return milliseconds;
    long old = legacySeconds * 1000L;
    return old >= MIN && old <= MAX ? (int) old : DEFAULT;
  }
}

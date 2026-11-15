package ru.bccontrol.sedonastudio.protocol;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Best-effort read batch: a bad component must not discard successful responses. */
public final class SampleBatch {
  @FunctionalInterface
  public interface Reader {
    Map<String, String> read(String id) throws Exception;
  }

  public record Result(
      Map<String, Map<String, String>> values,
      Map<String, String> errors,
      Map<String, Long> times) {}

  public static Result read(Collection<String> ids, Reader reader, BooleanSupplier connected) {
    Map<String, Map<String, String>> values = new LinkedHashMap<>();
    Map<String, String> errors = new LinkedHashMap<>();
    Map<String, Long> times = new LinkedHashMap<>();
    for (String id : ids) {
      if (!connected.getAsBoolean()) break;
      try {
        values.put(id, Map.copyOf(reader.read(id)));
        times.put(id, System.nanoTime());
      } catch (Exception e) {
        String reason = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        errors.put(id, reason.length() > 512 ? reason.substring(0, 512) : reason);
      }
    }
    return new Result(values, errors, times);
  }
}

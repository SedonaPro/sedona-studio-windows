package ru.bccontrol.panel.api;

import java.util.*;

/** Immutable, transport-neutral data. No editor IDs, model classes or credentials. */
public final class PanelData {
  private PanelData() {}

  public enum Quality {
    GOOD,
    STALE,
    OFFLINE,
    LOCAL,
    ERROR
  }

  public enum Capability {
    READ,
    WRITE,
    ACTION
  }

  public enum Outcome {
    CONFIRMED,
    REJECTED,
    UNKNOWN
  }

  public record Ref(String componentPath, String field) {
    public Ref {
      Objects.requireNonNull(componentPath);
      Objects.requireNonNull(field);
    }
  }

  public record Field(
      String name, String type, boolean readable, boolean writable, boolean action) {}

  public record Component(String path, String name, String type, List<Field> fields) {
    public Component {
      fields = List.copyOf(fields);
    }

    public String toString() {
      return path + " · " + type;
    }
  }

  public record Sample(String value, Quality quality, long timestampMillis, String detail) {}

  public record Snapshot(
      String session,
      boolean connected,
      List<Component> components,
      Map<Ref, Sample> values,
      Set<Capability> capabilities) {
    public Snapshot {
      components = List.copyOf(components);
      values = Map.copyOf(values);
      capabilities = Set.copyOf(capabilities);
    }
  }

  public record CommandResult(Outcome outcome, String message) {}
}

package ru.bccontrol.panel.demo;

import static ru.bccontrol.panel.api.PanelData.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import ru.bccontrol.panel.api.*;

/** Deterministic mock host; no network, Studio, Sedona library or credentials. */
public final class DemoHost implements PanelHost {
  private final Set<Consumer<Snapshot>> listeners = new LinkedHashSet<>();
  private double value = 20;
  private boolean enabled = true;
  private String document = "";
  private long tick;

  public Snapshot snapshot() {
    List<Field> fields =
        List.of(
            new Field("value", "float", true, true, false),
            new Field("enabled", "bool", true, true, false),
            new Field("reset", "action", false, false, true));
    Map<Ref, Sample> values =
        Map.of(
            new Ref("/demo", "value"),
            new Sample(
                String.format(Locale.ROOT, "%.2f", value),
                Quality.GOOD,
                System.currentTimeMillis(),
                PanelText.of("panelmodule.demo.name", "Demo")),
            new Ref("/demo", "enabled"),
            new Sample(
                Boolean.toString(enabled),
                Quality.GOOD,
                System.currentTimeMillis(),
                PanelText.of("panelmodule.demo.name", "Demo")));
    return new Snapshot(
        PanelText.of("panelmodule.demo.name", "Demo"),
        true,
        List.of(
            new Component(
                "/demo",
                PanelText.of("panelmodule.demo.short", "Demo host"),
                "mock::Signal",
                fields)),
        values,
        Set.of(Capability.READ, Capability.WRITE, Capability.ACTION));
  }

  public Subscription subscribe(Consumer<Snapshot> listener) {
    listeners.add(listener);
    listener.accept(snapshot());
    return () -> listeners.remove(listener);
  }

  public void watch(Set<Ref> refs) {}

  public void tick() {
    tick++;
    if (enabled) value = 20 + 10 * Math.sin(tick * .1);
    publish();
  }

  private void publish() {
    Snapshot snapshot = snapshot();
    for (var listener : List.copyOf(listeners)) listener.accept(snapshot);
  }

  public CompletionStage<CommandResult> write(Ref ref, String value) {
    try {
      if (!ref.componentPath().equals("/demo"))
        throw new IllegalArgumentException(
            PanelText.of("panelmodule.demo.noComponent", "No such component"));
      if (ref.field().equals("enabled")) {
        if (!Set.of("true", "false").contains(value))
          throw new IllegalArgumentException(
              PanelText.of("panelmodule.demo.booleanExpected", "Boolean expected"));
        enabled = Boolean.parseBoolean(value);
      } else if (ref.field().equals("value")) {
        double n = Double.parseDouble(value);
        if (!Double.isFinite(n))
          throw new IllegalArgumentException(
              PanelText.of("panelmodule.demo.notFinite", "Not a finite number"));
        this.value = n;
      } else
        throw new IllegalArgumentException(
            PanelText.of("panelmodule.demo.noField", "No such field"));
      publish();
      return CompletableFuture.completedFuture(
          new CommandResult(
              Outcome.CONFIRMED, PanelText.of("panelmodule.demo.written", "Demo: written")));
    } catch (RuntimeException ex) {
      return CompletableFuture.completedFuture(
          new CommandResult(Outcome.REJECTED, ex.getMessage()));
    }
  }

  public CompletionStage<CommandResult> invoke(Ref ref, String argument) {
    if (!ref.equals(new Ref("/demo", "reset")))
      return CompletableFuture.completedFuture(
          new CommandResult(
              Outcome.REJECTED, PanelText.of("panelmodule.demo.noAction", "No such action")));
    value = 0;
    tick = 0;
    publish();
    return CompletableFuture.completedFuture(
        new CommandResult(
            Outcome.CONFIRMED, PanelText.of("panelmodule.demo.reset", "Demo: reset")));
  }

  public String loadDocument() {
    return document;
  }

  public void saveDocument(String value) {
    if (value.length() > 262144)
      throw new IllegalArgumentException(
          PanelText.of("panelmodule.demo.documentTooLarge", "The document is larger than 256 KiB"));
    document = value;
  }
}

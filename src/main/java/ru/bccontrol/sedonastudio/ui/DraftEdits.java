package ru.bccontrol.sedonastudio.ui;

import java.util.*;
import java.util.function.*;
import ru.bccontrol.sedonastudio.model.*;

/** Local commands wait for a device snapshot to settle; transport jobs are never retried. */
final class DraftEdits {
  private final History history;
  private final BooleanSupplier busy;
  private final Runnable changed;
  private final Consumer<String> status;
  private final Deque<Runnable> pending = new ArrayDeque<>();
  private List<String> structure;

  DraftEdits(History history, BooleanSupplier busy, Runnable changed, Consumer<String> status) {
    this.history = history;
    this.busy = busy;
    this.changed = changed;
    this.status = status;
  }

  void edit(Consumer<Project> change) {
    submit(
        () -> {
          history.edit(change);
          changed.run();
        });
  }

  void undo() {
    submit(
        () -> {
          if (history.undo()) changed.run();
        });
  }

  void redo() {
    submit(
        () -> {
          if (history.redo()) changed.run();
        });
  }

  private void submit(Runnable command) {
    if (!busy.getAsBoolean()) {
      command.run();
      return;
    }
    if (pending.size() >= 128) {
      status.accept(Tr.of("edit.queueFull"));
      return;
    }
    if (pending.isEmpty()) structure = signature(history.get());
    pending.add(command);
    status.accept(Tr.of("edit.queued"));
  }

  void settled(boolean success) {
    if (pending.isEmpty()) return;
    if (!success || !Objects.equals(structure, signature(history.get()))) {
      pending.clear();
      status.accept(Tr.of("edit.repeatAfterOperation"));
      return;
    }
    while (!pending.isEmpty()) {
      try {
        pending.removeFirst().run();
      } catch (RuntimeException e) {
        pending.clear();
        status.accept(Tr.of("edit.repeatAfterOperation"));
        return;
      }
    }
  }

  private static List<String> signature(Project p) {
    List<String> result = new ArrayList<>();
    result.add(p.name);
    result.add(p.platform);
    result.addAll(p.parts);
    for (Project.Node n : p.nodes.values()) {
      result.addAll(Arrays.asList(n.id, n.name, n.parent, n.type, n.kitPart));
      for (Project.Port port : n.ports) result.add(port.id + ":" + port.name + ":" + port.type);
    }
    return result;
  }
}

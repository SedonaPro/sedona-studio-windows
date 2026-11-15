package ru.bccontrol.sedonastudio.ui;

import java.util.*;
import java.util.concurrent.atomic.*;
import ru.bccontrol.sedonastudio.model.*;

public final class DraftEditsTests {
  public static void main(String[] args) {
    History history = new History();
    Project project = new Project();
    Project.Node node = new Project.Node();
    node.id = "1"; node.name = "block"; node.type = "math::Add2"; node.kitPart = "math-test";
    project.nodes.put(node.id, node);
    history.reset(project);
    AtomicBoolean busy = new AtomicBoolean(true);
    AtomicInteger changes = new AtomicInteger();
    List<String> statuses = new ArrayList<>();
    DraftEdits edits = new DraftEdits(history, busy::get, changes::incrementAndGet, statuses::add);
    edits.edit(p -> p.nodes.get("1").x = 120);
    edits.edit(p -> p.nodes.get("1").note = "after read");
    edits.undo(); edits.redo();
    check(history.get().nodes.get("1").x == 0 && changes.get() == 0, "queued without mutation");
    history.reset(history.get()); // device readback replaces the history while a command waits
    busy.set(false); edits.settled(true);
    check(history.get().nodes.get("1").x == 120 && history.get().nodes.get("1").note.equals("after read") && changes.get() == 4, "replayed after readback");
    busy.set(true); edits.edit(p -> p.nodes.get("1").x = 999);
    busy.set(false); edits.settled(false);
    check(history.get().nodes.get("1").x == 120, "failed operation discards queue, never retries");
    busy.set(true); edits.edit(p -> p.nodes.get("1").x = 555);
    history.get().nodes.get("1").name = "replacement";
    busy.set(false); edits.settled(true);
    check(history.get().nodes.get("1").x == 120, "changed structure discards stale command");
    edits.undo(); edits.redo();
    check(history.get().nodes.get("1").x == 120, "ordinary history remains usable");
    check(statuses.size() == 8, "busy and discarded actions give nonmodal status");
    System.out.println("PASS deferred edits 6 checks: busy move/comment/undo/redo, readback, failure, structure guard, history, nonmodal status");
  }
  private static void check(boolean ok, String text) { if (!ok) throw new AssertionError(text); }
}

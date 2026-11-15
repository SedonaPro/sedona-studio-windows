package ru.bccontrol.sedonastudio.model;

import java.util.*;
import java.util.function.Consumer;

/** Whole-document snapshots are bounded; runtime samples are never editor commands. */
public final class History {
  private final Deque<Project> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
  private Project project = new Project();

  public Project get() {
    return project;
  }

  public void reset(Project p) {
    project = p.copy();
    undo.clear();
    redo.clear();
  }

  public void edit(Consumer<Project> action) {
    Project next = project.copy();
    action.accept(next);
    List<String> errors = next.validate();
    if (!errors.isEmpty()) throw new IllegalArgumentException(errors.get(0));
    undo.push(project);
    while (undo.size() > 100) undo.removeLast();
    project = next;
    redo.clear();
  }

  public boolean undo() {
    if (undo.isEmpty()) return false;
    redo.push(project);
    project = undo.pop();
    return true;
  }

  public boolean redo() {
    if (redo.isEmpty()) return false;
    undo.push(project);
    project = redo.pop();
    return true;
  }
}

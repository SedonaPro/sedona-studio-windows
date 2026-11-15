package ru.bccontrol.sedonastudio.ui;

import java.util.*;
import ru.bccontrol.sedonastudio.model.*;

/**
 * What the diagram canvas may ask of the editor and tell it: the project and view to draw, and the
 * user's gestures (select, move, connect, bend, open folder) turned into undoable edits.
 */
final class DiagramHost implements DiagramPane.Host {
  private final History history;
  private final EditorFocus focus;
  private final ViewOptions view;
  private final Notices notices;
  private final LiveValues liveValues;
  private final InspectorPane inspectorPane;
  private final EditActions editing;
  private final EditorShell shell;

  DiagramHost(
      History history,
      EditorFocus focus,
      ViewOptions view,
      Notices notices,
      LiveValues liveValues,
      InspectorPane inspectorPane,
      EditActions editing,
      EditorShell shell) {
    this.history = history;
    this.focus = focus;
    this.view = view;
    this.notices = notices;
    this.liveValues = liveValues;
    this.inspectorPane = inspectorPane;
    this.editing = editing;
    this.shell = shell;
  }

  public Project project() {
    return history.get();
  }

  public String folder() {
    return focus.folder;
  }

  public Set<String> selection() {
    return focus.selectedIds();
  }

  public boolean highlighted() {
    return view.highlightSignal;
  }

  public void selectGroup(Set<String> ids) {
    shell.selectGroup(ids);
  }

  public void moveGroup(Map<String, double[]> positions) {
    shell.edit(p -> EditorCommands.move(p, positions));
  }

  public void select(String id, boolean additive) {
    if (!additive) {
      shell.selectGroup(id == null ? Set.of() : Set.of(id));
      return;
    }
    LinkedHashSet<String> ids = new LinkedHashSet<>(focus.selectedIds());
    if (!ids.add(id)) ids.remove(id);
    shell.selectGroup(ids);
  }

  public String selected() {
    return focus.selected;
  }

  public Project.Wire selectedWire() {
    return focus.selectedWire;
  }

  public double zoom() {
    return view.zoom;
  }

  public boolean avoidOverlap() {
    return view.avoidOverlap;
  }

  public boolean snap() {
    return view.snapGrid;
  }

  public boolean showValues() {
    return view.showValues;
  }

  public ValueState valueState(Project.Node n, String port) {
    return liveValues.state(n, port);
  }

  public String quality(Project.Node n, String port) {
    return liveValues.quality(n, port);
  }

  public String display(Project.Node n, String port) {
    return liveValues.display(n, port);
  }

  public void select(String id) {
    focus.selection.clear();
    focus.selected = id;
    focus.selectedWire = null;
    shell.renderTree();
    inspectorPane.renderInspector();
  }

  public String signal(Project.Wire wire) {
    return inspectorPane.signalDescription(wire);
  }

  public void navigateWire(Project.Wire wire, boolean source) {
    inspectorPane.navigateWireEndpoint(wire, source);
  }

  public void selectWire(Project.Wire wire) {
    focus.selectedWire = wire;
    focus.selected = null;
    inspectorPane.renderInspector();
  }

  /** Help text of a type; set by StudioApp (from the SDK's kit sources). */
  java.util.function.Function<String, String> typeHelp = type -> "";

  public String help(Project.Node n) {
    return typeHelp.apply(n.type);
  }

  public java.util.List<Project.Caption> captions() {
    Project p = history.get();
    if (!TypeIcons.showNotes) return java.util.List.of();
    java.util.List<Project.Caption> shown = new java.util.ArrayList<>();
    for (Project.Caption c : p.captions)
      // A caption whose folder is gone shows up at the top level instead of being lost.
      if (c.folder.equals(focus.folder)
          || (focus.folder.equals("0") && !c.folder.equals("0") && !p.nodes.containsKey(c.folder)))
        shown.add(c);
    return shown;
  }

  public String addCaption(double x, double y) {
    String id = "c-" + java.util.UUID.randomUUID();
    shell.edit(
        p -> {
          Project.Caption c = new Project.Caption();
          c.id = id;
          c.folder = focus.folder;
          c.x = Math.max(0, x);
          c.y = Math.max(0, y);
          p.captions.add(c);
        });
    return id;
  }

  public void changeCaption(String id, java.util.function.Consumer<Project.Caption> change) {
    shell.edit(
        p -> {
          Project.Caption c = p.caption(id);
          if (c != null) change.accept(c);
        });
  }

  public void removeCaption(String id) {
    shell.edit(p -> p.captions.removeIf(c -> c.id.equals(id)));
  }

  public void comment(String id, String text) {
    if (text.length() > 4000) throw new IllegalArgumentException(Tr.of("store.noteTooLong"));
    shell.edit(
        p -> {
          Project.Node n = p.nodes.get(id);
          n.note = text;
          // A written comment is meant to be seen; removing it leaves the help text setting alone.
          if (!text.isBlank()) { n.info = true; n.infoSource = "comment"; }
        });
  }

  public void icon(String id, String icon) {
    shell.edit(p -> p.nodes.get(id).icon = icon);
  }

  public void resize(String id, double width) {
    shell.edit(p -> p.nodes.get(id).width = width);
  }

  public void move(String id, double x, double y) {
    shell.edit(p -> EditorCommands.move(p, java.util.Map.of(id, new double[] {x, y})));
  }

  public void bend(Project.Wire wire, int index, double x, double y) {
    shell.edit(
        p -> {
          Project.Wire w =
              p.wires.stream().filter(v -> v.key().equals(wire.key())).findFirst().orElseThrow();
          Project.Point point =
              new Project.Point(
                  Math.max(0, Math.round(x / 12) * 12), Math.max(0, Math.round(y / 12) * 12));
          if (index < 0) w.bends.add(point);
          else w.bends.set(index, point);
        });
  }

  public void clearBends(Project.Wire wire) {
    shell.edit(
        p ->
            p.wires.stream()
                .filter(v -> v.key().equals(wire.key()))
                .findFirst()
                .orElseThrow()
                .bends
                .clear());
  }

  public void connect(String a, String ap, String b, String bp) {
    shell.edit(p -> p.connect(a, ap, b, bp));
    notices.message(Tr.of("diagram.linkAdded"));
  }

  public void openFolder(String id) {
    focus.folder = id;
    focus.selected = null;
    focus.selectedWire = null;
    shell.render();
  }

  public void duplicate(String id) {
    focus.selected = id;
    editing.duplicate();
  }

  public void delete(String id) {
    if (id != null) {
      focus.selected = id;
      focus.selectedWire = null;
    }
    editing.remove();
  }

  public void message(String text) {
    notices.message(text);
  }

  public void error(Exception e) {
    notices.error(e);
  }
}

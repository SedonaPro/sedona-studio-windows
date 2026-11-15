package ru.bccontrol.sedonastudio.ui;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javafx.application.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.shape.*;
import javafx.stage.*;
import ru.bccontrol.sedonastudio.model.*;
import ru.bccontrol.sedonastudio.protocol.*;
import ru.bccontrol.sedonastudio.storage.*;

/**
 * Editing commands on the draft: add, copy/paste, remove, arrange, move to folder and templates.
 */
final class EditActions {
  private final History history;
  private final EditorFocus focus;
  private final DeviceLink deviceLink;
  private final Notices notices;
  private final TypeCatalog typeCatalog;
  private final PaletteDock paletteDock;
  private final EditorShell shell;

  private Project clipboard;

  EditActions(
      History history,
      EditorFocus focus,
      DeviceLink deviceLink,
      Notices notices,
      TypeCatalog typeCatalog,
      PaletteDock paletteDock,
      EditorShell shell) {
    this.history = history;
    this.focus = focus;
    this.deviceLink = deviceLink;
    this.notices = notices;
    this.typeCatalog = typeCatalog;
    this.paletteDock = paletteDock;
    this.shell = shell;
  }

  private String unique(String stem) {
    String s = stem.replaceAll("[^A-Za-z0-9_]", "");
    if (s.isEmpty() || Character.isDigit(s.charAt(0))) s = "block";
    for (int i = 1; ; i++) {
      String suffix = "_" + i;
      String name = s.substring(0, Math.min(s.length(), 7 - suffix.length())) + suffix;
      boolean exists = false;
      for (Project.Node n : history.get().nodes.values())
        if (n.parent.equals(focus.folder) && n.name.equals(name)) exists = true;
      if (!exists) return name;
    }
  }

  void addBlock() {
    Project.Node template = paletteDock.palette.selected();
    if (template == null) return;
    addBlockAt(
        template,
        100 + (history.get().nodes.size() % 4) * 235,
        90 + (history.get().nodes.size() % 5) * 170);
  }

  /**
   * Switches the project to another build of a kit ("name-checksum") that the palette knows: the
   * blocks of that kit take the new build and its ports; values of ports that still exist stay,
   * links to ports that are gone are dropped. One undoable step. Returns how many blocks changed.
   */
  int useKitBuild(String part) {
    if (deviceLink.session.connected())
      throw new IllegalStateException(Tr.of("files.paletteLocked"));
    String kit = sedona.KitPart.parse(part).name;
    Map<String, Project.Node> fresh = new HashMap<>();
    for (Project.Node type : typeCatalog.types)
      if (type.kitPart.equals(part)) fresh.put(type.type, type);
    if (fresh.isEmpty()) throw new IllegalStateException(Tr.of("edit.kitNotInPalette", part));
    int[] changed = {0};
    shell.edit(
        p -> {
          p.parts.replaceAll(
              existing -> sedona.KitPart.parse(existing).name.equals(kit) ? part : existing);
          if (!p.parts.contains(part)) p.parts.add(part);
          for (Project.Node n : p.nodes.values()) {
            if (n.kitPart == null || !sedona.KitPart.parse(n.kitPart).name.equals(kit)) continue;
            Project.Node type = fresh.get(n.type);
            if (type == null) throw new IllegalStateException(Tr.of("edit.typeGone", n.type, part));
            Map<String, String> kept = new HashMap<>(n.values);
            n.kitPart = part;
            n.typeIcon = type.typeIcon;
            n.ports.clear();
            n.values.clear();
            for (Project.Port port : type.ports) {
              n.ports.add(port.copy());
              String value =
                  kept.containsKey(port.name) ? kept.get(port.name) : type.values.get(port.name);
              if (value != null) n.values.put(port.name, value);
            }
            if (kept.containsKey("meta")) n.values.put("meta", kept.get("meta"));
            changed[0]++;
          }
          p.wires.removeIf(
              w -> {
                Project.Node a = p.nodes.get(w.source), b = p.nodes.get(w.target);
                return a == null
                    || b == null
                    || a.port(w.sourcePort) == null
                    || b.port(w.targetPort) == null;
              });
        });
    return changed[0];
  }

  void addBlockAt(Project.Node template, double x, double y) {
    List<String> required =
        typeCatalog.dependencies.getOrDefault(template.kitPart, List.of(template.kitPart));
    for (String part : required) {
      String kit = sedona.KitPart.parse(part).name;
      for (String existing : history.get().parts)
        if (sedona.KitPart.parse(existing).name.equals(kit) && !existing.equals(part))
          throw new IllegalArgumentException(Tr.of("edit.kitMismatch", existing, part));
    }
    Project.Node n = template.copy();
    n.id = "draft-" + UUID.randomUUID();
    n.parent = focus.folder;
    n.name = unique(n.type.substring(n.type.indexOf("::") + 2));
    n.x = Math.max(0, Math.min(3060, Math.round(x / 12) * 12));
    n.y = Math.max(0, Math.min(3060, Math.round(y / 12) * 12));
    shell.edit(
        p -> {
          p.nodes.put(n.id, n);
          for (String part : required) if (!p.parts.contains(part)) p.parts.add(part);
        });
    focus.selected = n.id;
    shell.render();
  }

  void copySelection() {
    if (focus.selectedIds().isEmpty()) return;
    clipboard = EditorCommands.fragment(history.get(), focus.selectedIds());
    notices.message(Tr.of("edit.copied", clipboard.nodes.size(), clipboard.wires.size()));
  }

  void pasteSelection() {
    if (clipboard == null || clipboard.nodes.isEmpty()) return;
    Set<String> added = new LinkedHashSet<>();
    shell.edit(p -> added.addAll(EditorCommands.paste(p, clipboard, focus.folder, 36)));
    shell.selectGroup(added);
    shell.world().selectionStyles();
  }

  void duplicate() {
    copySelection();
    pasteSelection();
  }

  void remove() {
    if (focus.selectedWire != null) {
      String key = focus.selectedWire.key();
      shell.edit(p -> p.wires.removeIf(w -> w.key().equals(key)));
      focus.selectedWire = null;
    } else {
      Set<String> ids = new LinkedHashSet<>(focus.selectedIds());
      if (ids.isEmpty()) return;
      shell.edit(
          p -> {
            for (String id : EditorCommands.roots(p, ids)) p.remove(id);
          });
      focus.selected = null;
      focus.selection.clear();
    }
    shell.render();
  }

  /** Puts the selected blocks onto the 12 px grid, each from where it is shown now. */
  void snapSelectionToGrid() {
    Set<String> ids = focus.selectedIds();
    if (ids.isEmpty()) return;
    Map<String, Bounds> boxes = shell.world().blockBounds();
    Map<String, double[]> positions = new LinkedHashMap<>();
    for (String id : ids) {
      Bounds b = boxes.get(id);
      if (b != null)
        positions.put(
            id,
            new double[] {Math.round(b.getMinX() / 12) * 12, Math.round(b.getMinY() / 12) * 12});
    }
    if (!positions.isEmpty()) shell.edit(p -> EditorCommands.move(p, positions));
  }

  void arrangeSelection(int mode) {
    Set<String> ids = focus.selectedIds();
    if (ids.size() < 2) return;
    List<String> ordered = new ArrayList<>(ids);
    Map<String, Bounds> boxes = shell.world().blockBounds();
    ordered.sort(
        Comparator.comparingDouble(
            id -> mode == 0 || mode == 2 ? boxes.get(id).getMinX() : boxes.get(id).getMinY()));
    double start =
        mode == 0 || mode == 2
            ? boxes.get(ordered.get(0)).getMinX()
            : boxes.get(ordered.get(0)).getMinY();
    boolean horizontal = mode == 0 || mode == 2;
    Bounds last = boxes.get(ordered.get(ordered.size() - 1));
    double end = horizontal ? last.getMaxX() : last.getMaxY();
    double total =
        ordered.stream()
            .mapToDouble(id -> horizontal ? boxes.get(id).getWidth() : boxes.get(id).getHeight())
            .sum();
    double gap = Math.max(24, (end - start - total) / (ordered.size() - 1));
    Map<String, double[]> positions = new LinkedHashMap<>();
    double cursor = start;
    for (String id : ordered) {
      Bounds b = boxes.get(id);
      double x = b.getMinX(), y = b.getMinY(), value = mode < 2 ? start : cursor;
      if (horizontal) x = value;
      else y = value;
      positions.put(id, new double[] {Math.round(x / 12) * 12, Math.round(y / 12) * 12});
      cursor += (horizontal ? b.getWidth() : b.getHeight()) + gap;
    }
    shell.edit(p -> EditorCommands.move(p, positions));
  }

  void moveToFolder() {
    List<String> targets = new ArrayList<>();
    targets.add(Tr.of("edit.rootItem"));
    for (Project.Node n : history.get().nodes.values())
      if (n.type.equals("sys::Folder")) targets.add(n.name + " [#" + n.id + "]");
    ChoiceDialog<String> dialog = new ChoiceDialog<>(targets.get(0), targets);
    dialog.initOwner(shell.stage());
    dialog.setHeaderText(Tr.of("edit.moveTitle"));
    dialog
        .showAndWait()
        .ifPresent(
            value -> {
              String target = value.substring(value.lastIndexOf("[#") + 2, value.length() - 1);
              Set<String> ids = new LinkedHashSet<>(focus.selectedIds());
              Set<String> added = new LinkedHashSet<>();
              shell.edit(p -> added.addAll(EditorCommands.relocate(p, ids, target)));
              focus.folder = target;
              shell.selectGroup(added);
              shell.render();
            });
  }

  void templates() {
    try {
      TemplateLibraryDialog.show(
          shell.stage(),
          new TemplateStore(SessionStore.root().resolve("templates")),
          () -> EditorCommands.fragment(history.get(), focus.selectedIds()),
          this::insertTemplate,
          notices::error,
          shell.prefs(),
          path -> shell.showDocument(path.toUri().toString()));
    } catch (Exception ex) {
      notices.error(ex);
    }
  }

  private void insertTemplate(Project fragment) {
    Project checked =
        TemplateRequirements.resolve(
                TemplateStore.prepare(fragment, fragment.nodes.keySet(), fragment.name))
            .project();
    if (deviceLink.session.connected()
        && deviceLink.baseline != null
        && !deviceLink.baseline.parts.containsAll(checked.parts))
      throw new IllegalArgumentException(Tr.of("edit.templateKitsMissing"));
    for (Project.Node n : checked.nodes.values()) {
      Project.Node definition =
          typeCatalog.types.stream()
              .filter(t -> t.type.equals(n.type) && t.kitPart.equals(n.kitPart))
              .findFirst()
              .orElseThrow(
                  () -> new IllegalArgumentException(Tr.of("edit.noExactType", n.type, n.kitPart)));
      n.ports.clear();
      n.typeIcon = definition.typeIcon;
      for (Project.Port port : definition.ports) n.ports.add(port.copy());
      n.values.keySet().removeIf(key -> !key.equals("meta") && n.port(key) == null);
    }
    Project placed = TemplatePlacement.place(history.get(), checked, focus.folder);
    Set<String> added = new LinkedHashSet<>();
    shell.edit(p -> added.addAll(EditorCommands.paste(p, placed, focus.folder, 0)));
    shell.selectGroup(added);
    shell.world().selectionStyles();
    shell.focusSelected();
    notices.message(Tr.of("edit.templateInserted"));
  }
}

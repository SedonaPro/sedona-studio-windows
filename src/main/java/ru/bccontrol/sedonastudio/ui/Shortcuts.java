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

/** Keyboard shortcuts of the main window. */
final class Shortcuts {
  private final History history;
  private final EditorFocus focus;
  private final DeviceLink deviceLink;
  private final Notices notices;
  private final ProjectFiles projectFiles;
  private final EditActions editing;
  private final SettingsDialogs dialogs;
  private final DiagramView diagramView;
  private final EditorShell shell;

  Shortcuts(
      History history,
      EditorFocus focus,
      DeviceLink deviceLink,
      Notices notices,
      ProjectFiles projectFiles,
      EditActions editing,
      SettingsDialogs dialogs,
      DiagramView diagramView,
      EditorShell shell) {
    this.history = history;
    this.focus = focus;
    this.deviceLink = deviceLink;
    this.notices = notices;
    this.projectFiles = projectFiles;
    this.editing = editing;
    this.dialogs = dialogs;
    this.diagramView = diagramView;
    this.shell = shell;
  }

  /** Keyboard shortcuts of the main window. */
  void install() {
    shell
        .scene()
        .addEventFilter(
            KeyEvent.KEY_PRESSED,
            e -> {
              if (e.getTarget() instanceof TextInputControl || deviceLink.busy.get()) return;
              if (e.getCode() == KeyCode.F1) {
                dialogs.help();
                e.consume();
              } else if (e.getCode() == KeyCode.HOME) {
                diagramView.fitDiagram();
                e.consume();
              } else if (e.isAltDown() && e.getCode() == KeyCode.UP) {
                diagramView.upFolder();
                e.consume();
              } else if (!navigationControl(e.getTarget())
                  && focus.selected != null
                  && (e.getCode() == KeyCode.LEFT
                      || e.getCode() == KeyCode.RIGHT
                      || e.getCode() == KeyCode.UP
                      || e.getCode() == KeyCode.DOWN)) {
                int step = e.isShiftDown() ? 1 : 12;
                int dx =
                    e.getCode() == KeyCode.LEFT ? -step : e.getCode() == KeyCode.RIGHT ? step : 0;
                int dy = e.getCode() == KeyCode.UP ? -step : e.getCode() == KeyCode.DOWN ? step : 0;
                notices.guard(
                    () ->
                        shell.edit(
                            p -> {
                              Map<String, double[]> positions = new LinkedHashMap<>();
                              for (String id : focus.selectedIds()) {
                                Project.Node n = p.nodes.get(id);
                                positions.put(id, new double[] {n.x + dx, n.y + dy});
                              }
                              EditorCommands.move(p, positions);
                            }));
                e.consume();
              } else if (e.getCode() == KeyCode.ESCAPE) {
                shell.world().cancelWire();
                e.consume();
              } else if (e.isControlDown() && e.getCode() == KeyCode.S) {
                notices.guard(projectFiles::save);
                e.consume();
              } else if (e.isControlDown() && e.getCode() == KeyCode.Z) {
                shell.undo();
                e.consume();
              } else if (e.isControlDown() && e.getCode() == KeyCode.Y) {
                shell.redo();
                e.consume();
              } else if (e.isControlDown() && e.getCode() == KeyCode.A) {
                shell.selectGroup(
                    history.get().nodes.values().stream()
                        .filter(n -> !n.id.equals("0") && n.parent.equals(focus.folder))
                        .map(n -> n.id)
                        .toList());
                shell.world().selectionStyles();
                e.consume();
              } else if (e.isControlDown() && e.getCode() == KeyCode.C) {
                editing.copySelection();
                e.consume();
              } else if (e.isControlDown() && e.getCode() == KeyCode.V) {
                notices.guard(editing::pasteSelection);
                e.consume();
              } else if (e.isControlDown() && e.getCode() == KeyCode.D) {
                notices.guard(editing::duplicate);
                e.consume();
              } else if (e.getCode() == KeyCode.DELETE) {
                notices.guard(editing::remove);
                e.consume();
              }
            });
  }

  private boolean navigationControl(Object target) {
    if (!(target instanceof Node)) return false;
    for (Node node = (Node) target; node != null; node = node.getParent())
      if (node instanceof TreeView
          || node instanceof ListView
          || node instanceof Slider
          || node instanceof ComboBox) return true;
    return false;
  }
}

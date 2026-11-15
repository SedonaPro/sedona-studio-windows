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

/** The saved view of the current session: open folder, zoom, scroll, panel sizes and tabs. */
final class SavedView {
  private final History history;
  private final EditorFocus focus;
  private final ViewOptions view;
  private final Notices notices;
  private final EditorShell shell;
  /** True while saving must wait, for example during startup; set by SessionWorkspace. */
  java.util.function.BooleanSupplier paused = () -> false;

  SplitPane horizontalPanels;
  SplitPane verticalPanels;
  TabPane leftTabs;
  TabPane bottomTabs;
  boolean restoringView = true;
  boolean restoredViewMatches;
  private final javafx.animation.PauseTransition viewSaveDelay =
      new javafx.animation.PauseTransition(javafx.util.Duration.millis(650));
  private ViewStateStore.State lastView;

  SavedView(
      History history, EditorFocus focus, ViewOptions view, Notices notices, EditorShell shell) {
    this.history = history;
    this.focus = focus;
    this.view = view;
    this.notices = notices;
    this.shell = shell;
  }

  java.nio.file.Path file() {
    return Paths.get(System.getProperty("studio.data"), "workspace.json");
  }

  ViewStateStore.State capture() {
    double[] h = horizontalPanels.getDividerPositions(), v = verticalPanels.getDividerPositions();
    return new ViewStateStore.State(
        ViewStateStore.projectKey(history.get()),
        focus.folder,
        ViewStateStore.lineage(history.get(), focus.folder),
        view.zoom,
        shell.diagramScroll().getHvalue(),
        shell.diagramScroll().getVvalue(),
        h[0],
        h[1],
        v[0],
        leftTabs.getSelectionModel().getSelectedIndex(),
        bottomTabs.getSelectionModel().getSelectedIndex());
  }

  void save() throws IOException {
    if (restoringView || horizontalPanels == null) return;
    ViewStateStore.State state = capture();
    if (!state.equals(lastView)) {
      ViewStateStore.save(state, file());
      lastView = state;
    }
  }

  void queueSave() {
    if (!paused.getAsBoolean() && !restoringView && !shell.launchArgs().contains("--smoke"))
      viewSaveDelay.playFromStart();
  }

  void install() {
    viewSaveDelay.setOnFinished(
        e -> {
          try {
            save();
          } catch (IOException ex) {
            notices.message(Tr.of("view.notSaved", ex.getMessage()));
          }
        });
    javafx.beans.InvalidationListener changed = o -> queueSave();
    shell.zoomControl().valueProperty().addListener(changed);
    shell.diagramScroll().hvalueProperty().addListener(changed);
    shell.diagramScroll().vvalueProperty().addListener(changed);
    for (var d : horizontalPanels.getDividers()) d.positionProperty().addListener(changed);
    for (var d : verticalPanels.getDividers()) d.positionProperty().addListener(changed);
    leftTabs.getSelectionModel().selectedIndexProperty().addListener(changed);
    bottomTabs.getSelectionModel().selectedIndexProperty().addListener(changed);
  }

  void restore() {
    restoringView = true;
    restoredViewMatches = false;
    try {
      var saved = ViewStateStore.load(file());
      if (saved.isEmpty()) {
        restoringView = false;
        return;
      }
      ViewStateStore.State state = saved.get();
      horizontalPanels.setDividerPositions(state.left(), state.right());
      verticalPanels.setDividerPositions(state.bottom());
      leftTabs.getSelectionModel().select(state.leftTab());
      bottomTabs.getSelectionModel().select(state.bottomTab());
      if (!state.project().equals(ViewStateStore.projectKey(history.get()))) {
        restoringView = false;
        return;
      }
      restoredViewMatches = true;
      focus.folder = ViewStateStore.restoreFolder(state, history.get());
      boolean folderMatches = focus.folder.equals(state.folder());
      shell.render();
      shell.zoomControl().setValue(state.zoom());
      shell.scene().getRoot().applyCss();
      shell.scene().getRoot().layout();
      Platform.runLater(
          () -> {
            shell.scene().getRoot().layout();
            shell.diagramScroll().setHvalue(folderMatches ? state.h() : 0);
            shell.diagramScroll().setVvalue(folderMatches ? state.v() : 0);
            lastView = capture();
            restoringView = false;
          });
    } catch (Exception ex) {
      restoringView = false;
      notices.message(Tr.of("view.notRestored", ex.getMessage()));
    }
  }

  /** Stop a pending delayed save, for shutdown. */
  void stop() {
    viewSaveDelay.stop();
  }
}

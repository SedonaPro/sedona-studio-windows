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
import ru.bccontrol.sedonastudio.integration.panel.*;
import ru.bccontrol.sedonastudio.model.*;
import ru.bccontrol.sedonastudio.protocol.*;
import ru.bccontrol.sedonastudio.storage.*;

/** Self-checks of dialogs: settings, change review, template library and recovery. */
final class SelfTestDialogs {
  private final StudioApp app;

  SelfTestDialogs(StudioApp app) {
    this.app = app;
  }

  void millisecondSettingsCheck() {
    boolean[] checked = {false};
    Throwable[] failure = {null};
    int previous = app.liveValues.liveIntervalMs;
    Platform.runLater(
        () -> {
          DialogPane pane = null;
          try {
            TextField field = null;
            for (Window w : Window.getWindows()) {
              Node found = w.getScene() == null ? null : w.getScene().lookup("#polling-ms");
              if (found instanceof TextField f) {
                field = f;
                break;
              }
            }
            if (field == null) throw new AssertionError("No millisecond field");
            for (Node n = field; n != null; n = n.getParent())
              if (n instanceof DialogPane d) {
                pane = d;
                break;
              }
            if (pane == null) throw new AssertionError("No settings pane");
            Node zoomNode = pane.lookup("#show-zoom-tools");
            if (!(zoomNode instanceof CheckBox))
              throw new AssertionError("No zoom visibility option");
            CheckBox zoomOption = (CheckBox) zoomNode;
            zoomOption.setSelected(!zoomOption.isSelected());
            field.setText("20");
            if (!pane.lookupButton(ButtonType.OK).isDisable())
              throw new AssertionError("Invalid ms accepted");
            field.setText("250");
            if (pane.lookupButton(ButtonType.OK).isDisable())
              throw new AssertionError("Valid ms rejected");
            checked[0] = true;
          } catch (Throwable ex) {
            failure[0] = ex;
          } finally {
            if (pane != null) ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
          }
        });
    boolean zoomWasVisible = app.scene.lookup("#zoom-tools").isVisible();
    app.dialogs.settings();
    if (app.scene.lookup("#zoom-tools").isVisible() != zoomWasVisible)
      throw new AssertionError("Cancel changed toolbar");
    if (!(app.scene.lookup("#studio-options") instanceof MenuButton options)
        || options.getItems().size() < 7) throw new AssertionError("Missing options commands");
    System.out.println(
        "PASS compact toolbar: grouped options, zoom visibility and cancel isolation");
    if (failure[0] != null) throw new AssertionError("Millisecond settings", failure[0]);
    if (!checked[0] || app.liveValues.liveIntervalMs != previous)
      throw new AssertionError("Cancel changed interval");
    System.out.println(
        "PASS millisecond settings: numeric field, bounds validation, valid 250ms and cancel"
            + " preserves settings");
  }

  void reviewDiagramChecks() throws Exception {
    app.projectFiles.demo();
    Project a = app.history.get().copy(), b = a.copy();
    b.nodes.get("demo0").values.put("out", "42");
    b.nodes.get("demo2").values.put("in1", "42");
    b.nodes.get("demo2").x += 24;
    b.nodes.remove("demo1");
    b.wires.removeIf(w -> w.source.equals("demo1"));
    Project.Node added = a.nodes.get("demo1").copy();
    added.id = "new";
    added.name = "new";
    b.nodes.put(added.id, added);
    b.wires.add(new Project.Wire("new", "out", "demo2", "in2"));
    Project.Node f = new Project.Node();
    f.id = "review-folder";
    f.name = "folder";
    f.type = "sys::Folder";
    f.kitPart = "sys-test";
    f.x = 40;
    f.y = 650;
    b.nodes.put(f.id, f);
    Project.Node nested = added.copy();
    nested.id = "nested";
    nested.parent = f.id;
    b.nodes.put(nested.id, nested);
    String originalA = ProjectStore.encode(a), originalB = ProjectStore.encode(b);
    var testPrefs =
        java.util.prefs.Preferences.userRoot()
            .node("/ru/bccontrol/sedonastudio/review-test-" + System.nanoTime());
    Stage preview = new Stage();
    try {
      ReviewDiagramPane pane = new ReviewDiagramPane(a, b, testPrefs);
      Scene previewScene = new Scene(pane, 1100, 650);

      preview.initOwner(app.stage);
      preview.setScene(previewScene);
      preview.show();
      pane.applyCss();
      pane.layout();
      pane.smokeChecks();
      if (!originalA.equals(ProjectStore.encode(a)) || !originalB.equals(ProjectStore.encode(b)))
        throw new AssertionError("Review changed inputs");
      var out =
          Paths.get(
              System.getProperty("studio.smokeOutput", "build/studio-preview.png") + ".review.png");
      Files.createDirectories(out.toAbsolutePath().getParent());
      javax.imageio.ImageIO.write(
          javafx.embed.swing.SwingFXUtils.fromFXImage(previewScene.snapshot(null), null),
          "png",
          out.toFile());
    } finally {
      preview.close();
      testPrefs.removeNode();
    }
  }

  void reviewPreviewChecks() {
    new ChangeListPane(
            List.of(
                new ProjectDiff.Change(Tr.of("diff.change"), "Alpha.in", "old-value", "2"),
                new ProjectDiff.Change(Tr.of("diff.addition"), "Beta", "—", "3"),
                new ProjectDiff.Change(Tr.of("diff.removal"), "Gamma", "4", "—")))
        .smokeChecks();
    Project oldBaseline = app.deviceLink.baseline;
    boolean oldPolling = app.liveValues.polling.isSelected();
    String original = ProjectStore.encode(app.history.get());
    Throwable[] failure = {null};
    boolean[] checked = {false};
    app.deviceLink.baseline = app.history.get().copy();
    Platform.runLater(
        () -> {
          DialogPane pane = null;
          try {
            for (Window window : Window.getWindows()) {
              Node node =
                  window.getScene() == null ? null : window.getScene().lookup("#changes-dialog");
              if (node instanceof DialogPane found) {
                pane = found;
                break;
              }
            }
            if (pane == null) throw new AssertionError("No preview dialog");
            if (!pane.getButtonTypes()
                .equals(javafx.collections.FXCollections.observableArrayList(ButtonType.CLOSE)))
              throw new AssertionError("Preview contains write button");
            if (pane.lookup("#change-search") == null)
              throw new AssertionError("No search controls");
            checked[0] = true;
          } catch (Throwable ex) {
            failure[0] = ex;
          } finally {
            if (pane != null) ((Button) pane.lookupButton(ButtonType.CLOSE)).fire();
          }
        });
    try {
      app.deviceChanges.previewChanges();
    } finally {
      app.deviceLink.baseline = oldBaseline;
    }
    if (failure[0] != null) throw new AssertionError("Preview", failure[0]);
    if (!checked[0]
        || oldPolling != app.liveValues.polling.isSelected()
        || !original.equals(ProjectStore.encode(app.history.get())))
      throw new AssertionError("Preview changed editor");
    Platform.runLater(
        () -> {
          DialogPane pane = null;
          try {
            for (Window window : Window.getWindows()) {
              Node node =
                  window.getScene() == null ? null : window.getScene().lookup("#changes-dialog");
              if (node instanceof DialogPane found) {
                pane = found;
                break;
              }
            }
            if (pane == null
                || pane.getButtonTypes().stream()
                    .noneMatch(t -> t.getText().equals(Tr.of("apply.wholePlan"))))
              throw new AssertionError("Missing full plan confirmation");
            ((TextField) pane.lookup("#change-search")).setText("no-matching-change");
          } catch (Throwable ex) {
            failure[0] = ex;
          } finally {
            if (pane != null) ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
          }
        });
    Boolean result =
        app.deviceChanges.reviewChanges(
            app.history.get().copy(), app.history.get().copy(), List.of("Test full plan"), true);
    if (result != null
        || failure[0] != null
        || !original.equals(ProjectStore.encode(app.history.get()))
        || oldPolling != app.liveValues.polling.isSelected())
      throw new AssertionError("Apply review cancellation", failure[0]);
    System.out.println(
        "PASS apply review cancellation: explicit full-plan confirmation, filtered list cannot"
            + " trigger writes, draft/polling unchanged");
    System.out.println(
        "PASS standalone comparison preview: close-only dialog, no connection required, search"
            + " visible, polling and draft unchanged");
  }

  private void snapshotDialog(DialogPane pane, String suffix) throws IOException {
    pane.applyCss();
    pane.layout();
    java.nio.file.Path out =
        Paths.get(
            System.getProperty("studio.smokeOutput", "build/studio-preview.png")
                + "."
                + suffix
                + ".png");
    Files.createDirectories(out.toAbsolutePath().getParent());
    javax.imageio.ImageIO.write(
        javafx.embed.swing.SwingFXUtils.fromFXImage(pane.getScene().snapshot(null), null),
        "png",
        out.toFile());
  }

  private DialogPane smokeDialog(String id) {
    for (Window window : Window.getWindows()) {
      Node n = window.getScene() == null ? null : window.getScene().lookup("#" + id);
      if (n instanceof DialogPane pane) return pane;
    }
    throw new AssertionError("Missing dialog " + id);
  }

  @SuppressWarnings("unchecked")
  void libraryRecoveryChecks() throws Exception {
    app.projectFiles.demo();
    app.selectGroup(Set.of("demo0", "demo2"));
    String original = ProjectStore.encode(app.history.get());
    Throwable[] failure = {null};
    boolean[] inserted = {false};
    Platform.runLater(
        () -> {
          DialogPane library = null;
          try {
            library = smokeDialog("template-library");
            Platform.runLater(
                () -> {
                  try {
                    DialogPane name = smokeDialog("template-name");
                    ((TextField) name.lookup(".text-field")).setText("UI template");
                    ((Button) name.lookupButton(ButtonType.OK)).fire();
                  } catch (Throwable ex) {
                    failure[0] = ex;
                  }
                });
            ((Button) library.lookup("#template-save")).fire();
            if (failure[0] != null) throw new AssertionError(failure[0]);
            ListView<TemplateStore.Entry> list =
                (ListView<TemplateStore.Entry>) library.lookup("#template-list");
            if (list.getItems().isEmpty()) throw new AssertionError("Template not saved");
            list.getSelectionModel().select(0);
            String beforePreview = ProjectStore.encode(app.history.get());
            Platform.runLater(
                () -> {
                  DialogPane preview = null;
                  try {
                    preview = smokeDialog("template-preview-dialog");
                    if (preview.getButtonTypes().size() != 1
                        || preview.lookupButton(ButtonType.CLOSE) == null)
                      throw new AssertionError("Preview has mutation controls");
                    ReviewDiagramPane pane =
                        (ReviewDiagramPane) preview.lookup("#template-preview-pane");
                    pane.previewSmokeChecks();
                    snapshotDialog(preview, "template");
                  } catch (Throwable ex) {
                    failure[0] = ex;
                  } finally {
                    if (preview != null) ((Button) preview.lookupButton(ButtonType.CLOSE)).fire();
                  }
                });
            ((Button) library.lookup("#template-preview-button")).fire();
            if (failure[0] != null || !beforePreview.equals(ProjectStore.encode(app.history.get())))
              throw new AssertionError("Preview changed editor", failure[0]);
            Platform.runLater(
                () -> {
                  DialogPane name = null;
                  try {
                    name = smokeDialog("template-rename-dialog");
                    ((TextField) name.lookup(".text-field")).setText("UI renamed");
                    ((Button) name.lookupButton(ButtonType.OK)).fire();
                  } catch (Throwable ex) {
                    failure[0] = ex;
                    if (name != null) ((Button) name.lookupButton(ButtonType.CANCEL)).fire();
                  }
                });
            ((Button) library.lookup("#template-rename")).fire();
            if (failure[0] != null
                || !list.getSelectionModel().getSelectedItem().name().equals("UI renamed"))
              throw new AssertionError("Rename UI", failure[0]);
            System.out.println(
                "PASS library preview/rename UI: actual buttons, close-only preview leaves editor"
                    + " unchanged, renamed entry stays selected");
            snapshotDialog(library, "library");
            ((Button) library.lookup("#template-insert")).fire();
            inserted[0] = true;
          } catch (Throwable ex) {
            failure[0] = ex;
            if (library != null) ((Button) library.lookupButton(ButtonType.CLOSE)).fire();
          }
        });
    app.editing.templates();
    if (failure[0] != null
        || !inserted[0]
        || app.history.get().nodes.size() != 5
        || app.history.get().wires.size() != 3)
      throw new AssertionError("Library UI save/insert", failure[0]);
    if (!app.history.undo() || !original.equals(ProjectStore.encode(app.history.get())))
      throw new AssertionError("Template insertion undo");
    app.render();
    java.nio.file.Path operation =
        Paths.get(System.getProperty("studio.data"), "operations", "ui-interrupted");
    Files.createDirectories(operation);
    ProjectStore.save(app.history.get(), operation.resolve("before.sedona.json"));
    ProjectStore.save(app.history.get(), operation.resolve("requested.sedona.json"));
    OperationJournal journal = new OperationJournal(operation);
    journal.attempt("ADD test");
    Files.writeString(operation.resolve("partial.log"), "");
    Platform.runLater(
        () -> {
          DialogPane pane = null;
          try {
            pane = smokeDialog("recovery-dialog");
            ListView<RecoveryStore.Operation> list =
                (ListView<RecoveryStore.Operation>) pane.lookup("#recovery-list");
            list.getSelectionModel().select(0);
            snapshotDialog(pane, "recovery");
            if (!list.getSelectionModel()
                .getSelectedItem()
                .state()
                .contains(Tr.of("recovery.required")))
              throw new AssertionError("Unknown operation state");
            Platform.runLater(
                () -> {
                  try {
                    DialogPane preview = smokeDialog("changes-dialog");
                    if (preview.getButtonTypes().size() != 1
                        || preview.lookupButton(ButtonType.CLOSE) == null)
                      throw new AssertionError("Recovery can write");
                    ((Button) preview.lookupButton(ButtonType.CLOSE)).fire();
                  } catch (Throwable ex) {
                    failure[0] = ex;
                  }
                });
            ((Button) pane.lookup("#recovery-compare")).fire();
          } catch (Throwable ex) {
            failure[0] = ex;
          } finally {
            if (pane != null) ((Button) pane.lookupButton(ButtonType.CLOSE)).fire();
          }
        });
    app.deviceChanges.recovery();
    if (failure[0] != null || !original.equals(ProjectStore.encode(app.history.get())))
      throw new AssertionError("Recovery UI", failure[0]);
    System.out.println(
        "PASS library UI: save actual selection with internal link, persistent entry, insert button"
            + " and one-step undo");
    System.out.println(
        "PASS recovery UI: interrupted operation selected, historical comparison close-only, editor"
            + " unchanged and no device writes");
  }
}

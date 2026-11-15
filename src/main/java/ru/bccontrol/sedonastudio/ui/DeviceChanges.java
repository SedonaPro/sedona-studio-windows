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
 * Writing to a device: review of the planned changes, explicit apply with readback, and the
 * recovery journal after an interrupted write.
 */
final class DeviceChanges {
  private final History history;
  private final DeviceLink deviceLink;
  private final Notices notices;
  private final LiveValues liveValues;
  private final EditorShell shell;
  private final DeviceConnection deviceConnection;

  DeviceChanges(
      History history,
      DeviceLink deviceLink,
      Notices notices,
      LiveValues liveValues,
      EditorShell shell,
      DeviceConnection deviceConnection) {
    this.history = history;
    this.deviceLink = deviceLink;
    this.notices = notices;
    this.liveValues = liveValues;
    this.shell = shell;
    this.deviceConnection = deviceConnection;
  }

  private final javafx.animation.PauseTransition autoDelay =
      new javafx.animation.PauseTransition(javafx.util.Duration.millis(700));

  /**
   * The draft changed. With "apply at once" on and a device connected, the changes are sent after a
   * short pause (so a drag is one operation), without the review dialog but with the same checks,
   * backups, journal and readback as Apply. App.save is not called.
   */
  void draftChanged(boolean enabled) {
    autoDelay.stop();
    if (!enabled || deviceLink.baseline == null || !deviceLink.session.connected()) return;
    autoDelay.setOnFinished(
        e -> {
          if (deviceLink.busy.get()) {
            autoDelay.playFromStart();
            return;
          }
          notices.guard(this::applyAtOnce);
        });
    autoDelay.playFromStart();
  }

  private void applyAtOnce() {
    if (deviceLink.baseline == null || !deviceLink.session.connected()) return;
    Project before = deviceLink.baseline.copy(), after = history.get().copy();
    List<String> steps = deviceLink.session.plan(before, after);
    if (steps.isEmpty()) return;
    java.nio.file.Path journal =
        Paths.get(
            System.getProperty("studio.data"),
            "operations",
            java.time.LocalDateTime.now().toString().replace(':', '-'));
    deviceLink.work(
        Tr.of("apply.atOnce", steps.size()),
        () -> deviceLink.session.apply(before, after, false, journal),
        p -> {
          deviceConnection.acceptRead(p, true);
          notices.message(Tr.of("apply.atOnceDone", steps.size()));
        });
  }

  void apply() {
    if (deviceLink.baseline == null || !deviceLink.session.connected())
      throw new IllegalStateException(Tr.of("apply.readFirst"));
    Project before = deviceLink.baseline.copy(), after = history.get().copy();
    List<String> steps = deviceLink.session.plan(before, after);
    if (steps.isEmpty()) {
      notices.message(Tr.of("apply.noChanges"));
      return;
    }
    Boolean persist = reviewChanges(before, after, steps, true);
    if (persist == null) return;
    java.nio.file.Path journal =
        Paths.get(
            System.getProperty("studio.data"),
            "operations",
            java.time.LocalDateTime.now().toString().replace(':', '-'));
    liveValues.pause();
    deviceLink.work(
        Tr.of("apply.applying", journal),
        () -> deviceLink.session.apply(before, after, persist, journal),
        p -> {
          deviceConnection.acceptRead(p);
          notices.message(Tr.of("apply.readBack", journal));
        });
  }

  void previewChanges() {
    if (deviceLink.baseline == null)
      throw new IllegalStateException(Tr.of("apply.readFirstToCompare"));
    Project before = deviceLink.baseline.copy(), after = history.get().copy();
    List<String> steps;
    try {
      steps = deviceLink.session.plan(before, after);
    } catch (RuntimeException ex) {
      steps = List.of(Tr.of("apply.planUnavailable", ex.getMessage()));
    }
    reviewChanges(before, after, steps, false);
  }

  Boolean reviewChanges(Project before, Project after, List<String> steps, boolean applying) {
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.initOwner(shell.stage());
    dialog.setTitle(applying ? Tr.of("apply.title", steps.size()) : Tr.of("apply.compareTitle"));
    dialog.getDialogPane().setId("changes-dialog");
    TextArea plan = new TextArea(String.join("\n", steps));
    plan.setEditable(false);
    plan.setPrefSize(650, 350);
    CheckBox save = new CheckBox(Tr.of("apply.callSave"));
    Label note = new Label(Tr.of("apply.warning"));
    ChangeListPane changes = new ChangeListPane(ProjectDiff.compare(before, after));
    ReviewDiagramPane diagrams = new ReviewDiagramPane(before, after, shell.prefs());
    diagrams.setStyle(shell.scene().getRoot().getStyle());
    Tab diagramTab = new Tab(Tr.of("apply.tabDiagrams"), diagrams);
    diagramTab.setOnSelectionChanged(
        e -> {
          if (diagramTab.isSelected()) diagrams.activate();
        });
    TabPane review =
        new TabPane(
            new Tab(Tr.of("apply.tabBeforeAfter"), changes),
            diagramTab,
            new Tab(Tr.of("apply.tabPlan"), plan));
    review.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    Label source = new Label(Tr.of("apply.compareHint"));
    dialog.setResizable(true);
    save.setVisible(applying);
    save.setManaged(applying);
    note.setVisible(applying);
    note.setManaged(applying);
    dialog.getDialogPane().setContent(new VBox(12, source, review, note, save));
    if (!applying) {
      dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
      dialog.showAndWait();
      return null;
    }
    ButtonType confirm = new ButtonType(Tr.of("apply.wholePlan"), ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
    return dialog.showAndWait().orElse(ButtonType.CANCEL) == confirm ? save.isSelected() : null;
  }

  void recovery() {
    try {
      RecoveryStore store =
          new RecoveryStore(Paths.get(System.getProperty("studio.data"), "operations"));
      Dialog<ButtonType> dialog = new Dialog<>();
      dialog.initOwner(shell.stage());
      dialog.setTitle(Tr.of("recovery.title"));
      dialog.getDialogPane().setId("recovery-dialog");
      dialog.setResizable(true);
      ListView<RecoveryStore.Operation> list = new ListView<>();
      list.setId("recovery-list");
      list.getItems().addAll(store.list());
      list.setPrefSize(820, 180);
      list.setPlaceholder(new Label(Tr.of("recovery.empty")));
      TextArea details = new TextArea();
      details.setEditable(false);
      details.setPrefSize(820, 260);
      Label guide = new Label(Tr.of("recovery.hint"));
      guide.setWrapText(true);
      Button compare = new Button(Tr.of("recovery.originalRequested")),
          folderButton = new Button(Tr.of("recovery.openFolder")),
          refresh = new Button(Tr.of("recovery.refresh"));
      compare.setId("recovery-compare");
      compare.setDisable(true);
      folderButton.setDisable(true);
      list.getSelectionModel()
          .selectedItemProperty()
          .addListener(
              (o, a, b) -> {
                details.setText(b == null ? "" : b.details());
                compare.setDisable(b == null);
                folderButton.setDisable(b == null);
              });
      folderButton.setOnAction(
          e -> {
            var op = list.getSelectionModel().getSelectedItem();
            if (op != null) shell.showDocument(op.directory().toUri().toString());
          });
      compare.setOnAction(
          e ->
              notices.guard(
                  () -> {
                    var op = list.getSelectionModel().getSelectedItem();
                    if (op == null) return;
                    try {
                      Project
                          before = ProjectStore.load(op.directory().resolve("before.sedona.json")),
                          requested =
                              ProjectStore.load(op.directory().resolve("requested.sedona.json"));
                      reviewChanges(before, requested, List.of(Tr.of("recovery.historic")), false);
                    } catch (Exception ex) {
                      throw new IllegalStateException(
                          Tr.of("recovery.snapshotsUnavailable", ex.getMessage()), ex);
                    }
                  }));
      refresh.setOnAction(
          e ->
              notices.guard(
                  () -> {
                    try {
                      list.getItems().setAll(store.list());
                    } catch (Exception ex) {
                      throw new IllegalStateException(ex);
                    }
                  }));
      ButtonType read = new ButtonType(Tr.of("recovery.rereadDevice"), ButtonBar.ButtonData.OTHER);
      VBox content =
          new VBox(
              10,
              guide,
              list,
              new HBox(8, compare, folderButton, refresh),
              details,
              new Label(Tr.of("recovery.journals", store.root())));
      content.setPadding(new Insets(12));
      dialog.getDialogPane().setContent(content);
      dialog.getDialogPane().getButtonTypes().addAll(read, ButtonType.CLOSE);
      dialog.getDialogPane().lookupButton(read).setDisable(!deviceLink.session.connected());
      if (dialog.showAndWait().orElse(ButtonType.CLOSE) == read) {
        java.nio.file.Path preserved =
            Paths.get(
                System.getProperty("studio.data"),
                "recovery-drafts",
                java.time.LocalDateTime.now().toString().replace(':', '-') + ".sedona.json");
        ProjectStore.save(history.get(), preserved);
        notices.message(Tr.of("recovery.draftSaved", preserved));
        deviceConnection.readDevice();
      }
    } catch (Exception ex) {
      notices.error(ex);
    }
  }
}

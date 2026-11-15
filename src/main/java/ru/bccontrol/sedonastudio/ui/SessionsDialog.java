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

/** The Sessions dialog: create, open, rename, move and remove sessions. */
final class SessionsDialog {
  private final Notices notices;
  private final SessionWorkspace workspace;
  private final EditorShell shell;

  SessionsDialog(Notices notices, SessionWorkspace workspace, EditorShell shell) {
    this.notices = notices;
    this.workspace = workspace;
    this.shell = shell;
  }

  void show() {
    try {
      Dialog<ButtonType> dialog = new Dialog<>();
      dialog.initOwner(shell.stage());
      dialog.setTitle(Tr.of("sessions.title"));
      ListView<SessionStore.Profile> profiles = new ListView<>();
      profiles.getItems().setAll(SessionStore.list());
      profiles.setPrefSize(520, 260);
      Label hint = new Label(Tr.of("sessions.hint"));
      Button create =
          shell.button(
              Tr.of("sessions.new"),
              () -> {
                TextInputDialog name = new TextInputDialog(Tr.of("device.defaultName"));
                name.initOwner(shell.stage());
                name.setHeaderText(Tr.of("sessions.name"));
                name.showAndWait()
                    .ifPresent(
                        value ->
                            notices.guard(
                                () -> {
                                  try {
                                    SessionStore.Profile p = SessionStore.create(value);
                                    profiles.getItems().setAll(SessionStore.list());
                                    profiles.getSelectionModel().select(p);
                                  } catch (IOException e) {
                                    throw new java.io.UncheckedIOException(e);
                                  }
                                }));
              });
      Button remove =
          shell.button(
              Tr.of("sessions.remove"),
              () -> {
                SessionStore.Profile chosen = profiles.getSelectionModel().getSelectedItem();
                if (chosen == null) return;
                Alert confirm =
                    new Alert(
                        Alert.AlertType.CONFIRMATION,
                        Tr.of("sessions.removeConfirm", chosen.name()),
                        ButtonType.YES,
                        ButtonType.NO);
                confirm.initOwner(shell.stage());
                if (confirm.showAndWait().orElse(ButtonType.NO) == ButtonType.YES)
                  try {
                    java.nio.file.Path archived = SessionStore.remove(chosen.id());
                    profiles.getItems().setAll(SessionStore.list());
                    notices.message(Tr.of("sessions.moved", archived));
                  } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                  }
              });
      remove.setDisable(true);
      profiles
          .getSelectionModel()
          .selectedItemProperty()
          .addListener(
              (o, a, b) ->
                  remove.setDisable(
                      b == null || b.id().equals("local") || b.id().equals(SessionStore.active())));
      Button sessionManifests =
          shell.button(
              Tr.of("sessions.manifests"),
              () -> {
                SessionStore.Profile chosen = profiles.getSelectionModel().getSelectedItem();
                if (chosen == null) return;
                try {
                  TextArea text = new TextArea(SessionStore.manifestSummary(chosen.id()));
                  text.setEditable(false);
                  text.setPrefSize(650, 350);
                  Dialog<ButtonType> info = new Dialog<>();
                  info.initOwner(shell.stage());
                  info.setTitle(Tr.of("sessions.manifestsOf", chosen.name()));
                  info.getDialogPane()
                      .setContent(new VBox(10, new Label(Tr.of("sessions.manifestsHint")), text));
                  info.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
                  info.showAndWait();
                } catch (IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              });
      CheckBox separate = workspace.newWindowOption();
      TextField location = new TextField(SessionStore.root().resolve("sessions").toString());
      location.setEditable(false);
      Button folder =
          shell.button(
              Tr.of("sessions.openFolder"),
              () -> {
                try {
                  java.nio.file.Path selectedFolder = Paths.get(location.getText());
                  Files.createDirectories(selectedFolder);
                  shell.showDocument(selectedFolder.toUri().toString());
                } catch (IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              });
      profiles
          .getSelectionModel()
          .selectedItemProperty()
          .addListener(
              (o, a, b) ->
                  location.setText(
                      b == null
                          ? SessionStore.root().resolve("sessions").toString()
                          : SessionStore.directory(b.id()).toString()));
      VBox content =
          new VBox(
              12,
              hint,
              profiles,
              new HBox(8, create, remove, sessionManifests),
              separate,
              new Label(Tr.of("sessions.storageFolder")),
              location,
              folder);
      content.setPadding(new Insets(12));
      dialog.getDialogPane().setContent(content);
      dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
      if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
        SessionStore.Profile chosen = profiles.getSelectionModel().getSelectedItem();
        if (chosen != null && !chosen.id().equals(SessionStore.active())) {
          workspace.saveBeforeSessionSwitch();
          workspace.finishSessionLaunch(SessionStore.open(chosen.id()), separate.isSelected());
        }
      }
    } catch (Exception e) {
      notices.error(e);
    }
  }
}

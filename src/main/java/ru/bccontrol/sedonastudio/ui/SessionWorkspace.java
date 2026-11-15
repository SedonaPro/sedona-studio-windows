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

/** Sessions, device tabs and the persisted window/view state of the current session. */
final class SessionWorkspace {
  private final History history;
  private final EditorFocus focus;
  private final SavedView savedView;
  private final DeviceLink deviceLink;
  private final Notices notices;
  private final LiveValues liveValues;
  private final PanelFeature panel;
  private final EditorShell shell;
  /** Continues a connection the user already confirmed in another window; set by StudioApp. */
  java.util.function.Consumer<String> onConfirmedConnect = token -> {};

  final TabPane deviceTabs = new TabPane();
  boolean updatingTabs;
  boolean restoringSession = true;

  private String tabSignature = "";

  SessionWorkspace(
      History history,
      EditorFocus focus,
      SavedView savedView,
      DeviceLink deviceLink,
      Notices notices,
      LiveValues liveValues,
      PanelFeature panel,
      EditorShell shell) {
    this.history = history;
    this.focus = focus;
    this.savedView = savedView;
    savedView.paused = () -> restoringSession;
    this.deviceLink = deviceLink;
    this.notices = notices;
    this.liveValues = liveValues;
    this.panel = panel;
    this.shell = shell;
  }

  String sessionName() {
    try {
      return SessionStore.read(SessionStore.active()).name();
    } catch (IOException e) {
      return SessionStore.active();
    }
  }

  List<String> sessionParts() throws IOException {
    java.nio.file.Path f = Paths.get(System.getProperty("studio.data"), "manifest-set.json");
    if (!Files.exists(f)) return List.of();
    List<String> result = new ArrayList<>();
    for (Object v : (List<?>) Json.object(Files.readString(f)).get("parts")) result.add((String) v);
    return result;
  }

  void shutdownSession() {
    savedView.stop();
    liveValues.pause();
    deviceLink.timer.shutdownNow();
    panel.close();
    deviceLink.io.submit(
        () -> {
          deviceLink.session.close();
          Platform.runLater(Platform::exit);
        });
    deviceLink.io.shutdown();
  }

  void resetSessionTab() {
    updatingTabs = true;
    try {
      deviceTabs.getTabs().stream()
          .filter(t -> SessionStore.active().equals(t.getUserData()))
          .findFirst()
          .ifPresent(t -> deviceTabs.getSelectionModel().select(t));
    } finally {
      updatingTabs = false;
    }
  }

  void refreshSessionTabs() {
    if (!shell.stage().isShowing()) return;
    try {
      List<SessionStore.Profile> profiles = new ArrayList<>();
      for (var p : SessionStore.list())
        if (p.id().equals(SessionStore.active()) || SessionStore.running(p.id()) != null)
          profiles.add(p);
      String signature = profiles.toString();
      if (signature.equals(tabSignature)) return;
      tabSignature = signature;
      updatingTabs = true;
      deviceTabs.getTabs().clear();
      for (var p : profiles) {
        Tab tab = new Tab(p.name());
        tab.setTooltip(new Tooltip(p.toString()));
        tab.setUserData(p.id());
        tab.setClosable(!p.id().equals(SessionStore.active()));
        tab.setOnCloseRequest(
            e -> {
              e.consume();
              try {
                SessionStore.requestClose(p.id());
                tab.setDisable(true);
                notices.message(Tr.of("workspace.closing", p.name()));
              } catch (Exception ex) {
                notices.error(ex);
              }
            });

        deviceTabs.getTabs().add(tab);
        if (p.id().equals(SessionStore.active())) deviceTabs.getSelectionModel().select(tab);
      }

    } catch (Exception e) {
      notices.message(Tr.of("workspace.tabs", e.getMessage()));
    } finally {
      updatingTabs = false;
    }
  }

  void serviceSessionTabs() {
    if (deviceLink.busy.get()) return;
    try {
      java.nio.file.Path dir = SessionStore.directory(SessionStore.active()),
          close = dir.resolve("close-hidden");
      if (Files.exists(close)) {
        Files.delete(close);
        if (!shell.stage().isShowing()) {
          saveBeforeSessionSwitch();
          shutdownSession();
          return;
        }
      }
      try (var files = Files.newDirectoryStream(dir, "activate-*.json")) {
        for (var request : files) {
          ru.bccontrol.sedonastudio.storage.WorkspaceRequest.Message command;
          try {
            var message =
                ru.bccontrol.sedonastudio.storage.WorkspaceRequest.consume(
                    request, ProcessHandle.current().pid());
            if (message.isEmpty()) continue;
            command = message.get();
          } catch (IOException invalid) {
            notices.message(invalid.getMessage());
            continue;
          }
          String token = command.token();
          if (command.action().equals("close")) {
            saveBeforeSessionSwitch();
            shutdownSession();
            return;
          }
          shell.stage().show();
          shell.stage().setIconified(false);
          shell.stage().toFront();
          refreshSessionTabs();
          Files.writeString(dir.resolve("ready-" + token), "ready");
          String connect = command.connect();
          if (!connect.isEmpty()) {
            if (deviceLink.session.connected()) {
              char[] secret = SessionStore.consumeConnection(SessionStore.active(), connect);
              Arrays.fill(secret, '\0');
              notices.message(Tr.of("workspace.alreadyConnected"));
            } else onConfirmedConnect.accept(connect);
          }
        }
      }
      refreshSessionTabs();
    } catch (Exception e) {
      notices.message(Tr.of("workspace.switching", e.getMessage()));
    }
  }

  CheckBox newWindowOption() {
    CheckBox option = new CheckBox(Tr.of("workspace.newWindow"));
    option.setSelected(shell.prefs().getBoolean("sessionNewWindow", false));
    option
        .selectedProperty()
        .addListener((o, a, b) -> shell.prefs().putBoolean("sessionNewWindow", b));
    return option;
  }

  void saveBeforeSessionSwitch() throws IOException {
    savedView.save();
    ProjectStore.save(
        history.get(), Paths.get(System.getProperty("studio.data"), "draft.sedona.json"));
  }

  void finishSessionLaunch(SessionStore.Launch launch, boolean newWindow) {
    deviceLink.work(
        Tr.of("workspace.opening"),
        () -> {
          try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
              if (Files.exists(launch.ready())) return true;
              if (!launch.process().isAlive())
                throw new IOException(Tr.of("workspace.windowNotStarted"));
              Thread.sleep(100);
            }
            throw new IOException(Tr.of("workspace.windowNotConfirmed"));
          } finally {
            Files.deleteIfExists(launch.ready());
          }
        },
        ready -> {
          if (!newWindow) shell.stage().hide();
          else notices.message(Tr.of("workspace.openedInNewWindow"));
          refreshSessionTabs();
        });
  }

  /** Session tab strip above the toolbar. */
  void configureTabs() {
    deviceTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
    deviceTabs.setStyle("-fx-open-tab-animation: NONE; -fx-close-tab-animation: NONE;");
    deviceTabs.setMinHeight(36);
    deviceTabs.setPrefHeight(36);
    deviceTabs.setMaxHeight(36);
    deviceTabs.setTabMinWidth(160);
    deviceTabs.setTabMaxWidth(260);
    deviceTabs
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, a, b) -> {
              if (updatingTabs || b == null || SessionStore.active().equals(b.getUserData()))
                return;
              if (deviceLink.busy.get()) {
                resetSessionTab();
                notices.message(Tr.of("workspace.waitBeforeSwitch"));
                return;
              }
              try {
                saveBeforeSessionSwitch();
                finishSessionLaunch(SessionStore.open((String) b.getUserData()), false);
              } catch (Exception ex) {
                resetSessionTab();
                notices.error(ex);
              }
            });
  }
}

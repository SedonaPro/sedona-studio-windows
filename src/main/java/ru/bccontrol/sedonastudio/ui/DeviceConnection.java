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
 * Connecting to a device and reading it: connect dialog, session hand-off, accepting a fresh
 * snapshot, disconnect. All transport runs on the device executor.
 */
final class DeviceConnection {
  private final History history;
  private final EditorFocus focus;
  private final DeviceLink deviceLink;
  private final Notices notices;
  private final TypeCatalog typeCatalog;
  private final PaletteDock paletteDock;
  private final LiveValues liveValues;
  private final PanelFeature panel;
  private final SavedView savedView;
  private final SessionWorkspace workspace;
  private final ProjectFiles projectFiles;
  private final EditorShell shell;
  /** Runs after a connection delivered its first snapshot; a hook for launch-flag driven checks. */
  Runnable afterConnect = () -> {};

  DeviceConnection(
      History history,
      EditorFocus focus,
      DeviceLink deviceLink,
      Notices notices,
      TypeCatalog typeCatalog,
      PaletteDock paletteDock,
      LiveValues liveValues,
      PanelFeature panel,
      SavedView savedView,
      SessionWorkspace workspace,
      ProjectFiles projectFiles,
      EditorShell shell) {
    this.history = history;
    this.focus = focus;
    this.deviceLink = deviceLink;
    this.notices = notices;
    this.typeCatalog = typeCatalog;
    this.paletteDock = paletteDock;
    this.liveValues = liveValues;
    this.panel = panel;
    this.savedView = savedView;
    this.workspace = workspace;
    this.projectFiles = projectFiles;
    this.shell = shell;
  }

  void connect() {
    try {
      if (deviceLink.busy.get()) return;
      if (deviceLink.session.connected()) throw new IllegalStateException(Tr.of("connect.already"));
      if (!projectFiles.discard()) return;
      Dialog<ButtonType> dialog = new Dialog<>();
      dialog.initOwner(shell.stage());
      dialog.setTitle(Tr.of("connect.title"));
      SessionStore.Profile profile = SessionStore.read(SessionStore.active());
      TextField host = new TextField(profile.host().isBlank() ? "127.0.0.1" : profile.host()),
          port = new TextField(Integer.toString(profile.port())),
          user = new TextField(profile.user());
      PasswordField password = new PasswordField();
      CheckBox remember = new CheckBox(Tr.of("connect.savePassword"));
      remember.setSelected(true);
      if (!profile.password().isEmpty())
        try {
          char[] stored = SessionStore.decrypt(profile);
          password.setText(new String(stored));
          Arrays.fill(stored, '\0');
        } catch (Exception e) {
          notices.message(Tr.of("connect.passwordUnavailable"));
        }
      GridPane grid = new GridPane();
      grid.setHgap(12);
      grid.setVgap(10);
      grid.addRow(0, new Label(Tr.of("connect.address")), host);
      grid.addRow(1, new Label(Tr.of("session.udpPort")), port);
      grid.addRow(2, new Label(Tr.of("connect.user")), user);
      grid.addRow(3, new Label(Tr.of("connect.password")), password);
      grid.add(remember, 0, 4, 2, 1);
      CheckBox separate = workspace.newWindowOption();
      grid.add(separate, 0, 5, 2, 1);
      dialog.getDialogPane().setContent(grid);
      dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
      if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
      int p = Integer.parseInt(port.getText());
      char[] secret = password.getText().toCharArray();
      password.clear();
      String hostname = host.getText().trim(), username = user.getText().trim();
      if (SessionStore.active().equals("local")
          || (!profile.host().isBlank()
              && (!profile.host().equalsIgnoreCase(hostname) || profile.port() != p))) {
        try {
          List<SessionStore.Profile> matches = SessionStore.matching(hostname, p, username);
          SessionStore.Profile device;
          if (matches.isEmpty()) device = SessionStore.create(hostname + ":" + p);
          else if (matches.size() == 1) device = matches.get(0);
          else {
            ChoiceDialog<SessionStore.Profile> choose = new ChoiceDialog<>(matches.get(0), matches);
            choose.initOwner(shell.stage());
            choose.setHeaderText(Tr.of("connect.chooseSession"));
            java.util.Optional<SessionStore.Profile> selectedProfile = choose.showAndWait();
            if (selectedProfile.isEmpty()) return;
            device = selectedProfile.get();
          }
          SessionStore.save(
              new SessionStore.Profile(
                  device.id(),
                  device.name(),
                  hostname,
                  p,
                  username,
                  remember.isSelected() ? SessionStore.encrypt(device.id(), secret) : ""));
          workspace.saveBeforeSessionSwitch();
          workspace.finishSessionLaunch(
              SessionStore.openForConnection(device.id(), secret), separate.isSelected());
          notices.message(Tr.of("connect.handedOver"));
        } finally {
          Arrays.fill(secret, '\0');
        }
        return;
      }
      boolean savePassword = remember.isSelected();
      deviceLink.work(
          Tr.of("connect.connecting"),
          () -> {
            try {
              SessionStore.Profile updated =
                  new SessionStore.Profile(
                      profile.id(),
                      profile.name(),
                      hostname,
                      p,
                      username,
                      savePassword ? SessionStore.encrypt(profile.id(), secret) : "");
              SessionStore.save(updated);
              return readSessionConnection(updated, secret);
            } finally {
              Arrays.fill(secret, '\0');
            }
          },
          this::connectedResult);
    } catch (Exception e) {
      notices.error(e);
    }
  }

  private void connectedResult(Object[] result) {
    typeCatalog.replace((List<Project.Node>) result[1]);
    acceptRead((Project) result[0]);
    paletteDock.filter();
    if (!savedView.restoredViewMatches) shell.fitDiagram();
    notices.message(
        Tr.of(
            "connect.connected",
            history.get().platform,
            history.get().nodes.size(),
            history.get().parts.size()));
    afterConnect.run();
  }

  void continueConfirmedConnection(String token) {
    deviceLink.work(
        Tr.of("connect.byRequest"),
        () -> {
          SessionStore.Profile profile = SessionStore.read(SessionStore.active());
          if (shell.launchArgs().contains("--connect-smoke")
              && (!profile.host().equals("127.0.0.1") || profile.port() != 13987))
            throw new IllegalArgumentException(Tr.of("connect.testLoopbackOnly"));
          return readSessionConnection(
              profile, SessionStore.consumeConnection(profile.id(), token));
        },
        this::connectedResult);
  }

  void acceptRead(Project p) {
    acceptRead(p, false);
  }

  /**
   * Makes a project read from the device the draft and the device snapshot. quiet is for changes
   * applied in the background: the open folder, the selection, cached values and trends stay.
   */
  void acceptRead(Project p, boolean quiet) {
    if (quiet) {
      String folder = focus.folder, selected = focus.selected;
      carryNotesAndBends(p);
      deviceLink.baseline = p.copy();
      history.reset(p);
      projectFiles.detachFromFile();
      focus.folder = p.nodes.containsKey(folder) ? folder : "0";
      focus.selected = selected != null && p.nodes.containsKey(selected) ? selected : null;
      liveValues.resume();
      shell.render();
      shell.world().refreshValues();
      return;
    }
    if (ViewStateStore.projectKey(history.get()).equals(ViewStateStore.projectKey(p)))
      try {
        savedView.save();
      } catch (IOException ex) {
        notices.message(Tr.of("view.notSaved", ex.getMessage()));
      }
    carryNotesAndBends(p);
    deviceLink.baseline = p.copy();
    history.reset(p);
    liveValues.restart();
    projectFiles.detachFromFile();
    focus.folder = "0";
    focus.selected = null;
    shell.render();
    shell.world().refreshValues();
    savedView.restore();
  }

  /** Notes and wire bends live only in the draft: carry them over to what the device returned. */
  private void carryNotesAndBends(Project p) {
    // Captions exist only in the drawing: what the device returned has none of its own.
    if (p.captions.isEmpty())
      for (Project.Caption old : history.get().captions) {
        Project.Caption c = old.copy();
        Project.Node a = history.get().nodes.get(old.anchor), b = p.nodes.get(old.anchor);
        if (a != null && b != null && a.type.equals(b.type) && a.name.equals(b.name)) {
          c.x = Math.max(0, Math.min(10000, c.x + b.x - a.x));
          c.y = Math.max(0, Math.min(10000, c.y + b.y - a.y));
        } else c.anchor = "";
        if (!c.folder.equals("0") && !p.nodes.containsKey(c.folder)) c.folder = "0";
        p.captions.add(c);
      }
    for (Project.Wire w : p.wires)
      for (Project.Wire old : history.get().wires)
        if (w.key().equals(old.key()) && w.bends.isEmpty()) {
          Project.Node a = history.get().nodes.get(w.source), b = history.get().nodes.get(w.target);
          if (a != null
              && b != null
              && a.name.equals(p.nodes.get(w.source).name)
              && b.name.equals(p.nodes.get(w.target).name)) w.bends.addAll(old.bends);
        }
    for (Project.Node n : p.nodes.values()) {
      Project.Node old = history.get().nodes.get(n.id);
      if (old != null && old.type.equals(n.type) && old.name.equals(n.name)) {
        if (n.note.isBlank()) n.note = old.note;
        n.width = old.width;
        n.icon = old.icon;
        n.info = old.info;
        n.infoSource = old.infoSource;
      }
    }
  }

  void readDevice() {
    if (!deviceLink.session.connected()) throw new IllegalStateException(Tr.of("connect.none"));
    if (!projectFiles.discard()) return;
    deviceLink.work(
        Tr.of("connect.reading"),
        deviceLink.session::read,
        p -> {
          acceptRead(p);
          notices.message(Tr.of("connect.read"));
        });
  }

  void disconnect() {
    disconnect(() -> {});
  }

  /** Disconnects, then runs the given step on the device thread (the simulator stops its VM). */
  void disconnect(Runnable afterClose) {
    liveValues.pause();
    if (deviceLink.busy.get()) throw new IllegalStateException(Tr.of("connect.waitForOperation"));
    deviceLink.work(
        Tr.of("connect.disconnecting"),
        () -> {
          deviceLink.session.close();
          afterClose.run();
          return true;
        },
        v -> {
          liveValues.showOffline();
          shell.world().refreshValues();
          panel.publish();
          notices.message(Tr.of("connect.disconnected"));
          shell.title();
        });
  }

  /** Connect, remember the device's kit set for this session and read its type catalog. */
  /**
   * Device thread: connects to an address that is not a session's device (the simulator) and reads
   * it. The session's remembered kit set is left alone. The password buffer is wiped.
   */
  Object[] connectTransient(String host, int port, String user, char[] secret) throws Exception {
    try {
      Project project = deviceLink.session.connect(host, port, user, secret);
      return new Object[] {project, deviceLink.session.catalog()};
    } finally {
      Arrays.fill(secret, '\0');
    }
  }

  /** JavaFX thread: makes the result of connectTransient the current draft and device snapshot. */
  void adopt(Object[] result) {
    connectedResult(result);
  }

  private Object[] readSessionConnection(SessionStore.Profile profile, char[] secret)
      throws Exception {
    try {
      Project project =
          deviceLink.session.connect(profile.host(), profile.port(), profile.user(), secret);
      Files.writeString(
          Paths.get(System.getProperty("studio.data"), "manifest-set.json"),
          Json.write(ProjectStore.map("parts", project.parts, "platform", project.platform)));
      return new Object[] {project, deviceLink.session.catalog()};
    } finally {
      Arrays.fill(secret, '\0');
    }
  }
}

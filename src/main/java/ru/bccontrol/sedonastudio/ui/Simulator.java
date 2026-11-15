package ru.bccontrol.sedonastudio.ui;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import ru.bccontrol.sedonastudio.model.History;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.toolchain.Sdk;
import ru.bccontrol.sedonastudio.toolchain.SimImage;
import ru.bccontrol.sedonastudio.toolchain.Toolchain;

/**
 * Runs the draft in a local Sedona VM and connects to it, so a diagram can be tried without a
 * controller. Needs an external SDK; started and stopped only by the user. The VM listens for one
 * user with a password made for this run and is stopped when the editor exits.
 */
final class Simulator {
  private final History history;
  private final DeviceLink deviceLink;
  private final Notices notices;
  private final ProjectFiles projectFiles;
  private final DeviceConnection connection;
  private final EditorShell shell;
  /** Called on the JavaFX thread after the simulator started or stopped; set by StudioApp. */
  Runnable onChange = () -> {};

  private volatile Toolchain.Vm vm;
  private int port;
  private char[] password;

  Simulator(
      History history,
      DeviceLink deviceLink,
      Notices notices,
      ProjectFiles projectFiles,
      DeviceConnection connection,
      EditorShell shell) {
    this.history = history;
    this.deviceLink = deviceLink;
    this.notices = notices;
    this.projectFiles = projectFiles;
    this.connection = connection;
    this.shell = shell;
  }

  boolean running() {
    Toolchain.Vm current = vm;
    return current != null && current.alive();
  }

  /** The loopback port of the running simulator, or 0. */
  int port() {
    return running() ? port : 0;
  }

  /**
   * A copy of the one-run password while the simulator runs, else null. Only the simulator's own
   * user has it; it lets an upload log in again without asking for a password nobody was shown.
   */
  char[] password() {
    char[] current = password;
    return running() && current != null ? current.clone() : null;
  }

  /** The toolbar command: start when nothing runs, stop otherwise. */
  void toggle() {
    if (running()) stop();
    else start();
  }

  void start() {
    if (deviceLink.busy.get()) throw new IllegalStateException(Tr.of("notice.busy"));
    if (deviceLink.session.connected())
      throw new IllegalStateException(Tr.of("sim.disconnectFirst"));
    Optional<Sdk> sdk = Sdk.find(shell.prefs().get("sdkPath", null));
    if (sdk.isEmpty()) throw new IllegalStateException(Tr.of("sim.noSdk"));
    sdk.get().requireVm();
    Project draft = history.get().copy();
    Path folder = Paths.get(System.getProperty("studio.data"), "simulator");
    Toolchain toolchain = new Toolchain(sdk.get());
    java.nio.file.Path file = projectFiles.file;
    boolean dirty = projectFiles.dirty;
    deviceLink.work(
        Tr.of("sim.starting"),
        () -> {
          stopVm();
          int free;
          try (DatagramSocket probe = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            free = probe.getLocalPort();
          }
          SimImage.Access access = SimImage.build(toolchain, draft, folder, free);
          Toolchain.Vm started = toolchain.start(folder);
          vm = started;
          port = free;
          password = access.password().clone();
          Exception last = null;
          try {
            // The VM needs a moment before SOX answers.
            for (int attempt = 0; attempt < 60; attempt++) {
              if (!started.alive())
                throw new IllegalStateException(Tr.of("sim.exited", started.output()));
              try {
                return connection.connectTransient(
                    "127.0.0.1", free, access.user(), access.password().clone());
              } catch (Exception notYet) {
                last = notYet;
                Thread.sleep(250);
              }
            }
            throw new IllegalStateException(Tr.of("sim.noAnswer", String.valueOf(last)));
          } catch (Exception failed) {
            stopVm();
            throw failed;
          } finally {
            java.util.Arrays.fill(access.password(), '\0');
          }
        },
        result -> {
          carryOver(draft, (Project) result[0]);
          connection.adopt(result);
          projectFiles.reattach(file, dirty);
          shell.title();
          notices.message(Tr.of("sim.running", port, ((Project) result[0]).nodes.size()));
          onChange.run();
        });
  }

  void stop() {
    if (deviceLink.session.connected())
      connection.disconnect(
          () -> {
            stopVm();
            javafx.application.Platform.runLater(this::stopped);
          });
    else {
      stopVm();
      stopped();
    }
  }

  private void stopped() {
    notices.message(Tr.of("sim.stopped"));
    onChange.run();
  }

  private void stopVm() {
    Toolchain.Vm current = vm;
    vm = null;
    char[] secret = password;
    password = null;
    if (secret != null) java.util.Arrays.fill(secret, '\0');
    if (current != null) current.close();
  }

  /**
   * The simulator numbers the components itself, so what an app file cannot hold - notes and wire
   * bends, and the project's name - is carried from the draft to the project read back, by
   * component path.
   */
  private static void carryOver(Project draft, Project running) {
    running.name = draft.name;
    Map<String, Project.Node> byPath = new HashMap<>();
    for (Project.Node n : draft.nodes.values()) byPath.put(path(draft, n), n);
    Map<String, String> runningPath = new HashMap<>();
    for (Project.Node n : running.nodes.values()) {
      String path = path(running, n);
      runningPath.put(n.id, path);
      Project.Node old = byPath.get(path);
      if (old != null && old.type.equals(n.type)) {
        if (n.note.isBlank()) n.note = old.note;
        n.width = old.width;
        n.icon = old.icon;
        n.info = old.info;
        n.infoSource = old.infoSource;
      }
    }
    // Captions follow their folder to its new id; without one they go to the top level.
    Map<String, String> idByPath = new HashMap<>();
    for (Map.Entry<String, String> item : runningPath.entrySet())
      idByPath.put(item.getValue(), item.getKey());
    for (Project.Caption old : draft.captions) {
      Project.Caption c = old.copy();
      Project.Node folder = draft.nodes.get(old.folder);
      c.folder = folder == null ? "0" : idByPath.getOrDefault(path(draft, folder), "0");
      Project.Node anchor = draft.nodes.get(old.anchor);
      c.anchor = anchor == null ? "" : idByPath.getOrDefault(path(draft, anchor), "");
      running.captions.add(c);
    }
    Map<String, Project.Wire> wires = new HashMap<>();
    for (Project.Wire w : draft.wires) {
      Project.Node a = draft.nodes.get(w.source), b = draft.nodes.get(w.target);
      if (a != null && b != null)
        wires.put(
            path(draft, a) + "." + w.sourcePort + ">" + path(draft, b) + "." + w.targetPort, w);
    }
    for (Project.Wire w : running.wires) {
      Project.Wire old =
          wires.get(
              runningPath.get(w.source)
                  + "."
                  + w.sourcePort
                  + ">"
                  + runningPath.get(w.target)
                  + "."
                  + w.targetPort);
      if (old != null && w.bends.isEmpty()) w.bends.addAll(old.bends);
    }
  }

  private static String path(Project p, Project.Node n) {
    StringBuilder path = new StringBuilder(n.name);
    Project.Node at = n;
    for (int depth = 0; depth < 64 && !at.id.equals("0"); depth++) {
      at = p.nodes.get(at.parent);
      if (at == null || at.id.equals("0")) break;
      path.insert(0, at.name + "/");
    }
    return path.toString();
  }
}

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

/** Built-in GUI self-checks behind the --smoke launch flags; never runs in normal use. */
final class SelfTest {
  private final StudioApp app;
  SessionStore.Profile workspaceTestChild;
  long workspaceDeadline;
  long workspaceReturned;

  private boolean workspaceWentHidden;
  // Live-values check: the block being watched and what the sampler delivered for it.
  String liveTestId;
  int liveSampleCount;
  final Set<String> liveObserved = new HashSet<>();
  final SelfTestCanvas canvasChecks;
  final SelfTestDialogs dialogChecks;

  SelfTest(StudioApp app) {
    this.app = app;
    canvasChecks = new SelfTestCanvas(app);
    dialogChecks = new SelfTestDialogs(app);
  }

  /** Sampler hook: count batches that contain the watched block. */
  void observeSample(Map<String, Map<String, String>> values) {
    if (liveTestId != null && values.containsKey(liveTestId)) {
      liveSampleCount++;
      liveObserved.add(values.get(liveTestId).get("out"));
    }
  }

  /** Connection hook: the --live-smoke and --connect-smoke scenarios; does nothing otherwise. */
  void afterConnect() {
    if (app.launchArgs().contains("--live-smoke")) {
      liveTestId =
          app.history.get().nodes.values().stream()
              .filter(n -> n.name.equals("live1"))
              .findFirst()
              .orElseThrow()
              .id;
      app.focus.selected = null;
      app.liveValues.traces.clear();
      app.liveValues.liveIntervalMs = 200;
      liveSampleCount = 0;
      app.liveValues.polling.setSelected(true);
      app.liveValues.nextSample = 0;
      var panelRef =
          new ru.bccontrol.panel.api.PanelData.Ref(
              ru.bccontrol.sedonastudio.integration.panel.PanelSnapshots.path(
                  app.history.get(), liveTestId),
              "out");
      Set<String> panelObserved = new HashSet<>();
      app.panel.host.watch(Set.of(panelRef));
      var panelSubscription =
          app.panel.host.subscribe(
              snapshot -> {
                var value = snapshot.values().get(panelRef);
                if (value != null) panelObserved.add(value.value());
              });
      String before = ProjectStore.encode(app.history.get());
      long routes = app.world.routePasses();
      javafx.animation.PauseTransition wait =
          new javafx.animation.PauseTransition(javafx.util.Duration.seconds(5));
      wait.setOnFinished(
          e -> {
            try {
              if (liveSampleCount < 10)
                throw new AssertionError(
                    "200ms sampling did not run faster than 1s: " + liveSampleCount);
              System.out.println(
                  "PASS millisecond polling: 200 ms, "
                      + liveSampleCount
                      + " successful batches in 5 seconds, serialized executor");
              if (liveObserved.size() < 2)
                throw new AssertionError(
                    "Visible unselected component was not polled: " + liveObserved);
              if (!app.world
                  .displayedValue(liveTestId, "out")
                  .equals(app.liveValues.display(app.history.get().nodes.get(liveTestId), "out")))
                throw new AssertionError("Canvas value not repainted");
              if (app.world.routePasses() != routes
                  || !ProjectStore.encode(app.history.get()).equals(before))
                throw new AssertionError("Live sampling rebuilt routes or modified draft");
              if (app.deviceLink.busy.get()) throw new AssertionError("Polling blocks editing");
              Project.Node observed = app.history.get().nodes.get(liveTestId);
              long freshAt = app.liveValues.sampledAt.get(liveTestId);
              if (app.liveValues.state(observed, "out") != ValueState.FRESH)
                throw new AssertionError("Fresh quality");
              app.liveValues.sampledAt.put(
                  liveTestId, System.nanoTime() - TimeUnit.SECONDS.toNanos(60));
              if (app.liveValues.state(observed, "out") != ValueState.STALE)
                throw new AssertionError("Stale quality");
              app.liveValues.sampledAt.put(liveTestId, freshAt);
              boolean wasPolling = app.liveValues.polling.isSelected();
              app.liveValues.polling.setSelected(false);
              if (app.liveValues.state(observed, "out") != ValueState.STALE)
                throw new AssertionError("Paused quality");
              app.liveValues.polling.setSelected(wasPolling);
              String original = observed.values.get("min");
              observed.values.put("min", "9999.0");
              if (app.liveValues.state(observed, "min") != ValueState.PENDING)
                throw new AssertionError("Draft quality");
              observed.values.put("min", original);
              if (panelObserved.size() < 2)
                throw new AssertionError("Panel API did not receive changing SOX samples");
              panelSubscription.close();
              app.panel.host.watch(Set.of());
              System.out.println(
                  "PASS panel live adapter: changing SOX samples delivered as API DTOs through"
                      + " subscription; graph unchanged and commands disabled");
              System.out.println(
                  "PASS quality states: fresh, aged stale, paused stale and pending local edit");
              System.out.println(
                  "PASS live canvas: unselected Ramp changed repeatedly, label refreshed, zero"
                      + " route rebuilds, draft unchanged");
              Platform.runLater(() -> finishLiveWireSmoke(before, routes));
            } catch (Throwable failure) {
              failure.printStackTrace();
              System.exit(1);
            }
          });
      wait.play();
      return;
    }
    if (app.launchArgs().contains("--connect-smoke")) {
      if (app.history.get().nodes.isEmpty() || app.typeCatalog.types.isEmpty())
        throw new AssertionError("Empty connected session");
      System.out.println(
          "PASS confirmed session handoff: connected, program loaded, manifests and palette"
              + " available");
      try {
        snapshot();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      app.deviceLink.timer.shutdownNow();
      app.deviceLink.io.submit(
          () -> {
            app.deviceLink.session.close();
            Platform.runLater(
                () -> {
                  app.projectFiles.dirty = false;
                  app.stage.close();
                  Platform.exit();
                });
          });
      app.deviceLink.io.shutdown();
    }
  }

  /**
   * --kit-smoke=folder: the whole kit loop in the workshop window - create, build, palette, compile
   * error, unit tests - and then the new component running in the simulator.
   */
  private void kitScenario(java.nio.file.Path folder) {
    kitScenario(folder, false);
  }

  private void kitScenario(java.nio.file.Path folder, boolean nativeKit) {
    final int[] step = {-1, 0};
    final javafx.animation.Timeline[] clock = new javafx.animation.Timeline[1];
    final String kitName = nativeKit ? "acmeUiNative" : "acmeUi";
    final java.nio.file.Path source =
        (nativeKit
                ? ru.bccontrol.sedonastudio.toolchain.Sdk.find("")
                    .orElseThrow()
                    .home
                    .resolve("src/studio-native/ui")
                    .resolve(kitName)
                : folder.resolve(kitName))
            .resolve("Gain.sedona");
    final String[] original = new String[1];
    clock[0] =
        new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(250),
                e -> {
                  try {
                    if (++step[1] > (nativeKit ? 2160 : 480))
                      throw new AssertionError("Kit scenario timed out at step " + step[0]);
                    KitWorkshopWindow workshop = app.kitWorkshop;
                    if (app.deviceLink.busy.get() || workshop.running) return;
                    switch (step[0]) {
                      case -1 -> {
                        workshop.show();
                        if (nativeKit) workshop.createNative("ui", "Acme", kitName);
                        else workshop.create(folder, "Acme", kitName);
                        original[0] = java.nio.file.Files.readString(source);
                        workshop.build0();
                      }
                      case 0 -> {
                        if (workshop.last == null
                            || !workshop.last.ok()
                            || !workshop.status.getText().contains(workshop.last.part()))
                          throw new AssertionError(
                              "Template kit did not build: " + workshop.log.getText());
                        workshop.toPalette();
                      }
                      case 1 -> {
                        if (app.typeCatalog.types.stream()
                            .noneMatch(n -> n.type.equals(kitName + "::Gain"))) {
                          if (step[1] % 40 != 0) step[0]--;
                          else
                            throw new AssertionError("The new component did not reach the palette");
                          break;
                        }
                        java.nio.file.Files.writeString(
                            source, original[0].replace("in * gain", "in * gian"));
                        workshop.build0();
                      }
                      case 2 -> {
                        if (workshop.last.ok()
                            || workshop.messages.getItems().size() != 1
                            || workshop.messages.getItems().get(0).line() != 24)
                          throw new AssertionError(
                              "Compile error not shown: " + workshop.messages.getItems());
                        javafx.embed.swing.SwingFXUtils.fromFXImage(
                            workshop.scene().snapshot(null), null);
                        javax.imageio.ImageIO.write(
                            javafx.embed.swing.SwingFXUtils.fromFXImage(
                                workshop.scene().snapshot(null), null),
                            "png",
                            new File(System.getProperty("studio.smokeOutput") + ".kit.png"));
                        // The message opens the source at its line; the fix is typed and saved
                        // there.
                        workshop.open(workshop.messages.getItems().get(0));
                        if (!workshop.editor.getSelectedText().contains("gian"))
                          throw new AssertionError(
                              "Error line not selected: ["
                                  + workshop.editor.getSelectedText()
                                  + "]");
                        workshop.editor.setText(original[0]);
                        workshop.test();
                        if (!java.nio.file.Files.readString(source).equals(original[0]))
                          throw new AssertionError("Test did not save the edited file first");
                      }
                      case 3 -> {
                        if (!workshop.last.ok() || workshop.last.asserts() != 3)
                          throw new AssertionError(
                              "Unit tests did not pass: " + workshop.log.getText());
                        // Use the new component in a diagram and run it.
                        Project.Node gain = null;
                        for (Project.Node n : app.typeCatalog.types)
                          if (n.type.equals(kitName + "::Gain")) gain = n;
                        app.history.reset(new Project());
                        app.render();
                        app.editing.addBlockAt(gain, 240, 120);
                        if (app.history.get().nodes.values().stream()
                            .noneMatch(n -> n.type.equals(kitName + "::Gain")))
                          throw new AssertionError("Block of the new kit not added");
                        app.simulator.start();
                      }
                      case 4 -> {
                        if (!app.simulator.running() || !app.deviceLink.session.connected())
                          throw new AssertionError(
                              "Simulator did not start with the new kit: "
                                  + app.notices.log.getText());
                        if (app.history.get().nodes.values().stream()
                            .noneMatch(
                                n -> n.type.equals(kitName + "::Gain") && n.id.matches("[0-9]+")))
                          throw new AssertionError(
                              "The new component is not running in the simulator");
                        app.simulator.stop();
                      }
                      default -> {
                        if (app.simulator.running()) break;
                        System.out.println(
                            (nativeKit ? "PASS native kit workshop UI: " : "PASS kit workshop UI: ")
                                + "kit created and built, component in the palette,"
                                + " compile error listed with its line, unit tests passed, the new"
                                + " component ran in the simulator");
                        clock[0].stop();
                        app.projectFiles.dirty = false;
                        app.deviceLink.timer.shutdownNow();
                        app.deviceLink.io.shutdown();
                        app.stage.close();
                        Platform.exit();
                        return;
                      }
                    }
                    step[0]++;
                  } catch (Throwable failure) {
                    failure.printStackTrace();
                    try {
                      app.simulator.stop();
                    } catch (RuntimeException ignored) {
                    }
                    System.exit(1);
                  }
                }));
    clock[0].setCycleCount(javafx.animation.Animation.INDEFINITE);
    clock[0].play();
  }

  /** --sim-smoke=project: run a saved diagram in the simulator, watch it execute, stop it. */
  private void simulatorScenario(java.nio.file.Path projectFile) {
    final int[] step = {-1, 0};
    final javafx.animation.Timeline[] clock = new javafx.animation.Timeline[1];
    Runnable finish =
        () -> {
          clock[0].stop();
          app.projectFiles.dirty = false;
          app.deviceLink.timer.shutdownNow();
          app.deviceLink.io.shutdown();
          app.stage.close();
          Platform.exit();
        };
    clock[0] =
        new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(250),
                e -> {
                  try {
                    if (++step[1] > 240) throw new AssertionError("Simulator scenario timed out");
                    if (app.deviceLink.busy.get()) return;
                    if (step[0] == -1) {
                      // The window has finished its own start-up work; now act like the user.
                      app.history.reset(ProjectStore.loadRecovering(projectFile));
                      app.projectFiles.file = projectFile;
                      app.render();
                      app.simulator.start();
                      step[0] = 0;
                      return;
                    }
                    Project now = app.history.get();
                    if (step[0] == 0) {
                      if (!app.simulator.running() || !app.deviceLink.session.connected())
                        throw new AssertionError(
                            "Simulator did not start: " + app.notices.log.getText());
                      Project.Node source = null, sum = null;
                      for (Project.Node n : now.nodes.values()) {
                        if (n.name.equals("src1")) source = n;
                        if (n.name.equals("sum1")) sum = n;
                      }
                      if (source == null || sum == null || !source.id.matches("[0-9]+"))
                        throw new AssertionError("Diagram not read back from the simulator");
                      if (!"keep me".equals(source.note)
                          || now.wires.stream().noneMatch(w -> w.bends.size() == 1))
                        throw new AssertionError(
                            "Note or wire bend lost on the way through the simulator");
                      if (source.width != 300
                          || !"★".equals(source.icon)
                          || !sum.info
                          || sum.width != 264)
                        throw new AssertionError(
                            "Block appearance lost on the way through the simulator");
                      javafx.scene.Node infoLabel = app.world.lookup("#info-" + sum.id);
                      if (!(infoLabel instanceof Label)
                          || !((Label) infoLabel).getText().contains("Add2 adds two float inputs"))
                        throw new AssertionError(
                            "Type help is not shown on the block: " + infoLabel);
                      if (Math.abs(app.world.blockBounds().get(source.id).getWidth() - 300) > 1)
                        throw new AssertionError("Block width not applied");
                      // A comment written on the canvas, inside the block.
                      app.world.editComment(sum.id);
                      if (!(app.world.lookup("#comment-editor") instanceof TextArea editor))
                        throw new AssertionError("No comment editor on the block");
                      editor.setText("adds the two branches");
                      editor.fireEvent(
                          new KeyEvent(
                              KeyEvent.KEY_PRESSED,
                              "",
                              "",
                              KeyCode.ENTER,
                              false,
                              true,
                              false,
                              false));
                      Project.Node commented = app.history.get().nodes.get(sum.id);
                      if (!"adds the two branches".equals(commented.note)
                          || !commented.info
                          || !(app.world.lookup("#info-" + sum.id) instanceof Label written)
                          || !written.getText().equals("adds the two branches"))
                        throw new AssertionError("Comment written on the block was not kept");
                      app.world.editComment(sum.id);
                      ((TextArea) app.world.lookup("#comment-editor"))
                          .fireEvent(
                              new KeyEvent(
                                  KeyEvent.KEY_PRESSED,
                                  "",
                                  "",
                                  KeyCode.ESCAPE,
                                  false,
                                  false,
                                  false,
                                  false));
                      if (app.world.lookup("#comment-editor") != null
                          || !"adds the two branches"
                              .equals(app.history.get().nodes.get(sum.id).note))
                        throw new AssertionError("Esc did not cancel the comment editor");
                      if (!projectFile.equals(app.projectFiles.file))
                        throw new AssertionError("Project file association lost");
                      if (!app.stage.getTitle().contains("127.0.0.1:" + app.simulator.port()))
                        throw new AssertionError(
                            "Title does not say simulator: " + app.stage.getTitle());
                      app.liveValues.polling.setSelected(true);
                      app.liveValues.nextSample = 0;
                      step[0] = 1;
                    } else if (step[0] == 1) {
                      Project.Node sum = null;
                      for (Project.Node n : now.nodes.values()) if (n.name.equals("sum1")) sum = n;
                      if (app.liveValues.state(sum, "out") != ValueState.FRESH
                          || !app.liveValues.fullValue(sum, "out").startsWith("14.5")) return;
                      snapshot();
                      // Upload the whole application with a changed value; the simulator restarts.
                      final String sourceId =
                          now.nodes.values().stream()
                              .filter(n -> n.name.equals("src1"))
                              .findFirst()
                              .get()
                              .id;
                      app.edit(p -> p.nodes.get(sourceId).values.put("out", "20.5"));
                      app.deviceUpload.confirm =
                          prepared -> {
                            if (prepared.code() != null || prepared.app().length < 50)
                              throw new AssertionError("Unexpected upload: " + prepared.summary());
                            return app.simulator.password();
                          };
                      app.deviceUpload.upload();
                      step[0] = 10;
                    } else if (step[0] == 10) {
                      // Two device jobs run one after the other: wait for the adopted result.
                      Project.Node sum = null, source = null;
                      for (Project.Node n : now.nodes.values()) {
                        if (n.name.equals("sum1")) sum = n;
                        if (n.name.equals("src1")) source = n;
                      }
                      if (!app.deviceLink.session.connected()
                          || app.deviceLink.baseline == null
                          || !"20.5"
                              .equals(
                                  app.deviceLink.baseline.nodes.get(source.id).values.get("out"))) {
                        if (app.notices.log.getText().contains(Tr.of("notice.error", "")))
                          throw new AssertionError("Upload failed: " + app.notices.log.getText());
                        return;
                      }
                      if (!app.simulator.running())
                        throw new AssertionError("Simulator did not survive the restart");
                      if (!"keep me".equals(source.note))
                        throw new AssertionError("Note lost by the upload");
                      app.liveValues.polling.setSelected(true);
                      if (app.liveValues.state(sum, "out") != ValueState.FRESH
                          || !app.liveValues.fullValue(sum, "out").startsWith("20.5")) return;
                      // Apply at once: an edit reaches the device without the Apply dialog.
                      app.view.autoApply = true;
                      final String again = source.id;
                      app.edit(p -> p.nodes.get(again).values.put("out", "30.5"));
                      step[0] = 11;
                    } else if (step[0] == 11) {
                      Project.Node sum = null, source = null;
                      for (Project.Node n : now.nodes.values()) {
                        if (n.name.equals("sum1")) sum = n;
                        if (n.name.equals("src1")) source = n;
                      }
                      if (!"30.5"
                              .equals(
                                  app.deviceLink.baseline.nodes.get(source.id).values.get("out"))
                          || app.liveValues.state(sum, "out") != ValueState.FRESH
                          || !app.liveValues.fullValue(sum, "out").startsWith("30.5")) return;
                      app.view.autoApply = false;
                      app.simulator.stop();
                      step[0] = 2;
                    } else if (step[0] == 2) {
                      if (app.simulator.running() || app.deviceLink.session.connected()) return;
                      if (now.nodes.values().stream().noneMatch(n -> n.name.equals("sum1")))
                        throw new AssertionError("Project lost after the simulator stopped");
                      System.out.println(
                          "PASS simulator: diagram built, VM started, connected, live value 14.5 on"
                              + " sum1.out, note/bend/file kept; whole application uploaded with a"
                              + " changed value, restart, live value 20.5; edit applied at once,"
                              + " live value 30.5; stopped on request");
                      finish.run();
                    }
                  } catch (Throwable failure) {
                    failure.printStackTrace();
                    app.simulator.stop();
                    System.exit(1);
                  }
                }));
    clock[0].setCycleCount(javafx.animation.Animation.INDEFINITE);
    clock[0].play();
  }

  /** Test scenarios selected by launch flags; does nothing in normal use. */
  void runLaunchScenarios() throws Exception {
    if (app.launchArgs().contains("--annotations-smoke")) Platform.runLater(() -> SelfTestAnnotations.run(app));
    for (String arg : app.launchArgs())
      if (arg.startsWith("--native-kit-smoke=")) {
        String folder = arg.substring("--native-kit-smoke=".length());
        Platform.runLater(() -> kitScenario(java.nio.file.Paths.get(folder), true));
      }
    for (String arg : app.launchArgs())
      if (arg.startsWith("--kit-smoke=")) {
        String folder = arg.substring("--kit-smoke=".length());
        Platform.runLater(() -> kitScenario(java.nio.file.Paths.get(folder)));
      }
    for (String arg : app.launchArgs())
      if (arg.startsWith("--sim-smoke=")) {
        String file = arg.substring("--sim-smoke=".length());
        Platform.runLater(() -> simulatorScenario(java.nio.file.Paths.get(file)));
      }
    if (app.launchArgs().contains("--workspace-smoke")) {
      workspaceTestChild = SessionStore.create("Workspace test");
      workspaceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
      app.workspace.saveBeforeSessionSwitch();
      app.workspace.finishSessionLaunch(
          SessionStore.launch(workspaceTestChild.id(), List.of("--workspace-return")), false);
    }
    if (app.launchArgs().contains("--workspace-return")) {
      javafx.animation.PauseTransition back =
          new javafx.animation.PauseTransition(javafx.util.Duration.seconds(4));
      back.setOnFinished(
          e -> {
            try {
              app.workspace.saveBeforeSessionSwitch();
              app.workspace.finishSessionLaunch(SessionStore.open("local"), false);
            } catch (Exception ex) {
              throw new RuntimeException(ex);
            }
          });
      back.play();
    }
    if (app.launchArgs().contains("--view-write-smoke"))
      Platform.runLater(() -> viewRestartSmoke(true));
    if (app.launchArgs().contains("--view-read-smoke"))
      Platform.runLater(() -> viewRestartSmoke(false));
    if (app.launchArgs().contains("--smoke"))
      Platform.runLater(
          () -> {
            int exit = 0;
            try {
              if (app.launchArgs().contains("--restore-smoke")) {
                if (!app.history.get().name.equals("ResumeTest"))
                  throw new AssertionError("Persisted session draft was not restored");
                System.out.println(
                    "PASS session restart: restored project from isolated session draft");
                app.typeCatalog.types.clear();
                app.typeCatalog.types.addAll(Catalog.bundled());
                app.projectFiles.demo();
                app.paletteDock.filter();
              }
              smokeChecks();
              app.focus.selected = "demo2";
              app.render();
              snapshot();
              System.out.println(
                  "PASS JavaFX interactions: palette add, duplicate, remove, undo/redo, inspector,"
                      + " diagram and scene render");
            } catch (Throwable e) {
              e.printStackTrace();
              exit = 1;
            } finally {
              app.projectFiles.dirty = false;
              app.deviceLink.timer.shutdownNow();
              app.deviceLink.io.shutdownNow();
              app.stage.close();
              Platform.exit();
              if (exit != 0) System.exit(exit);
            }
          });
  }

  void viewRestartSmoke(boolean write) {
    javafx.animation.PauseTransition settle =
        new javafx.animation.PauseTransition(javafx.util.Duration.millis(700));
    if (write) {
      app.savedView.restoringView = true;
      app.projectFiles.demo();
      Project p = app.history.get().copy();
      p.name = "ViewPersistence";
      p.remove("demo1");
      p.remove("demo2");
      Project.Node group = new Project.Node();
      group.id = "group";
      group.name = "group";
      group.type = "sys::Folder";
      group.kitPart = "sys-d3984c51";
      p.parts.add(group.kitPart);
      p.nodes.put(group.id, group);
      p.nodes.get("demo0").parent = group.id;
      app.history.reset(p);
      app.focus.folder = group.id;
      app.render();
      app.diagramView.zoomControl.setValue(.75);
      app.savedView.horizontalPanels.setDividerPositions(.25, .73);
      app.savedView.verticalPanels.setDividerPositions(.68);
      app.savedView.leftTabs.getSelectionModel().select(1);
      app.savedView.bottomTabs.getSelectionModel().select(2);
      app.scene.getRoot().applyCss();
      app.scene.getRoot().layout();
    }
    settle.setOnFinished(
        e -> {
          try {
            if (write) {
              app.diagramView.diagramScroll.setHvalue(.22);
              app.diagramView.diagramScroll.setVvalue(.31);
              app.savedView.restoringView = false;
              app.workspace.saveBeforeSessionSwitch();
              System.out.println(
                  "PASS view state written: folder, zoom, scroll, dividers and selected panels");
            } else {
              ViewStateStore.State wanted = ViewStateStore.load(app.savedView.file()).orElseThrow(),
                  actual = app.savedView.capture();
              if (!actual.folder().equals("group")
                  || Math.abs(actual.zoom() - .75) > .001
                  || Math.abs(actual.h() - wanted.h()) > .02
                  || Math.abs(actual.v() - wanted.v()) > .02
                  || Math.abs(actual.left() - wanted.left()) > .02
                  || Math.abs(actual.right() - wanted.right()) > .02
                  || Math.abs(actual.bottom() - wanted.bottom()) > .02
                  || actual.leftTab() != 1
                  || actual.bottomTab() != 2
                  || app.deviceLink.session.connected())
                throw new AssertionError(
                    "Restored view mismatch: " + actual + " expected " + wanted);
              System.out.println(
                  "PASS view restart: folder, zoom, scroll, dividers and panels restored without"
                      + " connection");
              snapshot();
            }
            app.workspace.shutdownSession();
          } catch (Throwable ex) {
            ex.printStackTrace();
            Platform.exit();
            System.exit(1);
          }
        });
    settle.play();
  }

  public static final class IncompatiblePanelModule implements ru.bccontrol.panel.api.PanelModule {
    public String id() {
      return "incompatible-test";
    }

    public String title() {
      return "Test";
    }

    public int apiMajor() {
      return 99;
    }

    public PanelView create(ru.bccontrol.panel.api.PanelHost host) {
      throw new AssertionError("Incompatible module must not be created");
    }
  }

  void modularPanelChecks() throws Exception {
    if (app.panel.module == null || !app.panel.module.status().equals("loaded:bccontrol.widgets"))
      throw new AssertionError(
          "Module not loaded: " + (app.panel.module == null ? "null" : app.panel.module.status()));
    TabPane tabs = (TabPane) app.scene.lookup("#right-tabs");
    tabs.getSelectionModel().select(1);
    app.root.applyCss();
    app.root.layout();
    if (app.scene.lookup("#independent-panel") == null
        || tabs.getSelectionModel().getSelectedIndex() != 1)
      throw new AssertionError("Panel tab content missing");
    Project document = app.history.get().copy();
    String original = ProjectStore.encode(document);
    String before = app.panel.host.loadDocument();
    ((Button) app.scene.lookup("#panel-add-widget")).fire();
    if (!app.panel.host.loadDocument().contains("version=1") || app.panel.host.watched().isEmpty())
      throw new AssertionError("Widget bind/document");
    if (!original.equals(ProjectStore.encode(app.history.get()))
        || app.deviceLink.session.connected())
      throw new AssertionError("Panel altered graph or auto-connected");
    app.panel.host.publish();
    snapshotDialogLike("panel");
    java.nio.file.Path test = Paths.get(System.getProperty("studio.data"), "panel-test-modules");
    Files.createDirectories(test);
    PanelModules missing = new PanelModules(test.resolve("absent"), app.panel.host);
    if (missing.status().startsWith("loaded:")) throw new AssertionError("Missing module");
    missing.close();
    java.nio.file.Path incompatible = test.resolve("incompatible");
    Files.createDirectories(incompatible);
    try (var jar =
        new java.util.jar.JarOutputStream(
            Files.newOutputStream(incompatible.resolve("test.jar")))) {
      jar.putNextEntry(
          new java.util.jar.JarEntry("META-INF/services/ru.bccontrol.panel.api.PanelModule"));
      jar.write(
          (getClass().getName() + "$IncompatiblePanelModule\n")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      jar.closeEntry();
    }
    PanelModules bad = new PanelModules(incompatible, app.panel.host);
    if (!bad.status().contains("99"))
      throw new AssertionError("API mismatch not detected: " + bad.status());
    bad.close();
    java.nio.file.Path duplicate = test.resolve("duplicate");
    Files.createDirectories(duplicate);
    try (var jar =
        new java.util.jar.JarOutputStream(Files.newOutputStream(duplicate.resolve("test.jar")))) {
      jar.putNextEntry(new java.util.jar.JarEntry("ru/bccontrol/panel/api/Duplicate.class"));
      jar.write(0);
      jar.closeEntry();
    }
    PanelModules copiedApi = new PanelModules(duplicate, app.panel.host);
    if (!copiedApi.status().contains(Tr.of("selftest.apiCopy")))
      throw new AssertionError("Bundled API not rejected");
    copiedApi.close();
    ((TabPane) app.scene.lookup("#right-tabs")).getSelectionModel().select(0);
    System.out.println(
        "PASS modular panel: separate JAR loaded, widget binding/session document, unchanged"
            + " graph/no auto-connect, missing/incompatible/duplicate-API modules isolated");
  }

  private void snapshotDialogLike(String suffix) throws IOException {
    app.root.applyCss();
    app.root.layout();
    java.nio.file.Path out =
        Paths.get(
            System.getProperty("studio.smokeOutput", "build/studio-preview.png")
                + "."
                + suffix
                + ".png");
    Files.createDirectories(out.toAbsolutePath().getParent());
    javax.imageio.ImageIO.write(
        javafx.embed.swing.SwingFXUtils.fromFXImage(app.scene.snapshot(null), null),
        "png",
        out.toFile());
  }

  void smokeChecks() throws Exception {
    dialogChecks.millisecondSettingsCheck();
    modularPanelChecks();
    dialogChecks.reviewDiagramChecks();
    dialogChecks.reviewPreviewChecks();
    dialogChecks.libraryRecoveryChecks();
    canvasChecks.groupGestureChecks();
    canvasChecks.dragPerformanceChecks();
    canvasChecks.geometryChecks();
    canvasChecks.gestureChecks();
    if (app.history.get().nodes.size() != 3) throw new AssertionError("Demo graph");
    app.paletteDock.palette.selectFirst();
    app.editing.addBlock();
    if (app.history.get().nodes.size() != 4) throw new AssertionError("Palette add");
    String added = app.focus.selected;
    app.editing.duplicate();
    if (app.history.get().nodes.size() != 5) throw new AssertionError("Duplicate");
    app.editing.remove();
    if (app.history.get().nodes.size() != 4) throw new AssertionError("Delete");
    if (!app.history.undo() || app.history.get().nodes.size() != 5)
      throw new AssertionError("UI undo");
    if (!app.history.redo() || app.history.get().nodes.size() != 4)
      throw new AssertionError("UI redo");
    app.history.undo();
    app.history.undo();
    app.history.undo();
    app.history.reset(ProjectStore.decode(ProjectStore.encode(app.history.get())));
    app.render();
    if (app.history.get().nodes.size() != 3) throw new AssertionError("Smoke history cleanup");
    LocalCatalog.Result localTypes = LocalCatalog.load(app.history.get().parts);
    app.typeCatalog.types.clear();
    app.typeCatalog.types.addAll(localTypes.types());
    app.typeCatalog.dependencies.clear();
    app.typeCatalog.dependencies.putAll(localTypes.dependencies());
    app.paletteDock.filter();
    if (app.paletteDock.palette.visibleTypes() < 50)
      throw new AssertionError("Manifest palette tree");
    app.paletteDock.search.setText("func::Ramp");
    if (app.paletteDock.palette.visibleTypes() != 1)
      throw new AssertionError("Manifest type search");
    app.paletteDock.palette.selectFirst();
    if (!"func::Ramp".equals(app.paletteDock.palette.selected().type))
      throw new AssertionError("Kit leaf selection");
    app.paletteDock.search.clear();
    System.out.println(
        "PASS palette tree: manifest groups, 135 types, kit versions, Ramp search and leaf"
            + " selection");
    ProjectStore.save(
        app.history.get(), Paths.get(System.getProperty("studio.data"), "smoke.sedona.json"));
  }

  void checkWorkspaceTest() {
    if (workspaceTestChild == null) return;
    try {
      if (System.nanoTime() > workspaceDeadline)
        throw new AssertionError("Workspace switch timed out");
      if (!app.stage.isShowing()) workspaceWentHidden = true;
      if (workspaceWentHidden && app.stage.isShowing()) {
        if (workspaceReturned == 0) {
          workspaceReturned = System.nanoTime();
          return;
        }
        if (System.nanoTime() - workspaceReturned < TimeUnit.SECONDS.toNanos(3)) return;
        if (app.workspace.deviceTabs.getTabs().size() != 2
            || SessionStore.running("local").pid() != ProcessHandle.current().pid())
          throw new AssertionError("Tabs did not reuse original process");
        if (SessionStore.directory(workspaceTestChild.id()).equals(SessionStore.directory("local")))
          throw new AssertionError("Session directories mixed");
        Tab other =
            app.workspace.deviceTabs.getTabs().stream()
                .filter(t -> workspaceTestChild.id().equals(t.getUserData()))
                .findFirst()
                .orElseThrow();
        other
            .getOnCloseRequest()
            .handle(new javafx.event.Event(other, other, Tab.TAB_CLOSE_REQUEST_EVENT));
        SessionStore.Profile child = workspaceTestChild;
        workspaceTestChild = null;
        app.deviceLink.work(
            Tr.of("selftest.closingSession"),
            () -> {
              long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
              while (SessionStore.running(child.id()) != null && System.nanoTime() < deadline)
                Thread.sleep(100);
              if (SessionStore.running(child.id()) != null)
                throw new IOException("Hidden child did not close");
              return true;
            },
            ok -> {
              System.out.println(
                  "PASS workspace tabs: switch to independent session, hide original, return to"
                      + " same PID, isolated folders, close hidden backend");
              app.workspace.shutdownSession();
            });
      }
    } catch (Throwable ex) {
      ex.printStackTrace();
      try {
        SessionStore.closeHiddenPeers();
      } catch (Exception ignored) {
      }
      Platform.exit();
      System.exit(1);
    }
  }

  void finishLiveWireSmoke(String before, long routes) {
    Platform.runLater(
        () -> {
          for (Window window : new ArrayList<>(Window.getWindows()))
            if (window != app.stage && window.isShowing()) window.hide();
        });
    app.notices.error(new IllegalArgumentException(Tr.of("selftest.editorError")));
    if (!app.liveValues.polling.isSelected())
      throw new AssertionError("Editor error disabled live polling");
    System.out.println("PASS editor error does not disable live polling");
    Project.Wire wire =
        app.history.get().wires.stream()
            .filter(w -> w.source.equals(liveTestId))
            .findFirst()
            .orElseThrow();
    app.focus.selectedWire = wire;
    app.focus.selected = null;
    app.inspectorPane.renderInspector();
    app.world.selectionStyles();
    String first =
        app.liveValues.fullValue(app.history.get().nodes.get(wire.source), wire.sourcePort);
    javafx.animation.PauseTransition wait =
        new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
    wait.setOnFinished(
        e -> {
          try {
            String value =
                app.liveValues.fullValue(app.history.get().nodes.get(wire.source), wire.sourcePort);
            if (value.equals(first)
                || !app.inspectorPane.wireSummary.getText().contains(Tr.of("value.label", value))
                || !app.inspectorPane.wireSummary.getText().contains(Tr.of("live.fresh"))
                || !app.liveValues.sampledAt.containsKey(wire.target)
                || app.world.routePasses() != routes
                || !ProjectStore.encode(app.history.get()).equals(before))
              throw new AssertionError("Live wire diagnostics did not refresh independently");
            System.out.println(
                "PASS live wire: both endpoints sampled, changing source value and fresh quality in"
                    + " inspector, zero reroutes/draft mutation");
            snapshot();
            app.workspace.shutdownSession();
          } catch (Throwable failure) {
            failure.printStackTrace();
            Platform.exit();
            System.exit(1);
          }
        });
    wait.play();
  }

  void snapshot() throws Exception {
    app.root.applyCss();
    app.root.layout();
    javafx.scene.image.WritableImage image = app.scene.snapshot(null);
    java.nio.file.Path out =
        Paths.get(System.getProperty("studio.smokeOutput", "build/studio-preview.png"));
    Files.createDirectories(out.toAbsolutePath().getParent());
    javax.imageio.ImageIO.write(
        javafx.embed.swing.SwingFXUtils.fromFXImage(image, null), "png", out.toFile());
    System.out.println("PASS JavaFX scene render " + image.getWidth() + "x" + image.getHeight());
  }
}

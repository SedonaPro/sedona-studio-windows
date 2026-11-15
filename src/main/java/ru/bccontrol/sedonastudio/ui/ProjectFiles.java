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

/** Project files and the local palette: new, open, save, demo project and local manifests. */
final class ProjectFiles {
  private final History history;
  private final EditorFocus focus;
  private final DeviceLink deviceLink;
  private final Notices notices;
  private final TypeCatalog typeCatalog;
  private final PaletteDock paletteDock;
  private final SessionWorkspace workspace;
  private final EditorShell shell;
  boolean dirty;
  java.nio.file.Path file;

  ProjectFiles(
      History history,
      EditorFocus focus,
      DeviceLink deviceLink,
      Notices notices,
      TypeCatalog typeCatalog,
      PaletteDock paletteDock,
      SessionWorkspace workspace,
      EditorShell shell) {
    this.history = history;
    this.focus = focus;
    this.deviceLink = deviceLink;
    this.notices = notices;
    this.typeCatalog = typeCatalog;
    this.paletteDock = paletteDock;
    this.workspace = workspace;
    this.shell = shell;
  }

  boolean discard() {
    if (!dirty) return true;
    Alert alert =
        new Alert(
            Alert.AlertType.CONFIRMATION, Tr.of("files.unsaved"), ButtonType.YES, ButtonType.NO);
    alert.initOwner(shell.stage());
    return alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
  }

  private Project.Node bundledDemoType(int index) {
    try {
      return Catalog.bundled().get(index).copy();
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  void loadLocalPalette() {
    if (deviceLink.session.connected())
      throw new IllegalStateException(Tr.of("files.paletteLocked"));
    List<String> preferred = List.copyOf(history.get().parts);
    deviceLink.work(
        Tr.of("files.readingManifests"),
        () ->
            SessionStore.active().equals("local")
                ? LocalCatalog.load(preferred)
                : LocalCatalog.exact(workspace.sessionParts()),
        result -> {
          typeCatalog.types.clear();
          typeCatalog.types.addAll(result.types());
          typeCatalog.dependencies.clear();
          typeCatalog.dependencies.putAll(result.dependencies());
          paletteDock.filter();
          notices.message(
              Tr.of("files.localPalette", typeCatalog.types.size(), result.warnings().size()));
          for (String warning : result.warnings())
            notices.message(Tr.of("files.manifest", warning));
        });
  }

  void demo() {
    Project p = new Project();
    p.name = Tr.of("files.demoName");
    for (int i = 0; i < 3; i++) {
      Project.Node n = bundledDemoType(i == 2 ? 2 : 0);
      n.id = "demo" + i;
      n.name = i == 2 ? "sum" : "input" + (i + 1);
      n.x = i == 2 ? 450 : 85;
      n.y = i == 1 ? 310 : 75;
      n.values.put(i == 2 ? "in1" : "out", i == 1 ? "20.0" : "10.0");
      p.nodes.put(n.id, n);
      if (!p.parts.contains(n.kitPart)) p.parts.add(n.kitPart);
    }
    p.connect("demo0", "out", "demo2", "in1");
    p.connect("demo1", "out", "demo2", "in2");
    history.reset(p);
    dirty = false;
    deviceLink.baseline = null;
    shell.render();
  }

  void newProject() {
    if (deviceLink.session.connected())
      throw new IllegalStateException(Tr.of("files.disconnectFirst"));
    if (!discard()) return;
    history.reset(new Project());
    deviceLink.baseline = null;
    file = null;
    focus.folder = "0";
    focus.selected = null;
    dirty = false;
    shell.render();
  }

  void open() {
    if (!discard()) return;
    FileChooser chooser = chooser();
    chooser.getExtensionFilters().add(appFilter());
    File chosen = chooser.showOpenDialog(shell.stage());
    if (chosen == null) return;
    try {
      // An app file is imported: it becomes an unsaved project, the file itself is not written to.
      boolean imported = AppFiles.isAppFile(chosen.toPath());
      // Manifests this session lacks are taken from a local SDK when there is one.
      Project p =
          imported
              ? AppFiles.read(
                  chosen.toPath(),
                  ru.bccontrol.sedonastudio.toolchain.Sdk.find(shell.prefs().get("sdkPath", null))
                      .map(ru.bccontrol.sedonastudio.toolchain.Sdk::kitSource)
                      .orElse(null))
              : ProjectStore.load(chosen.toPath());
      if (deviceLink.session.connected()
          && (deviceLink.baseline == null
              || !p.parts.equals(deviceLink.baseline.parts)
              || !p.nodes.containsKey("0")))
        throw new IllegalArgumentException(Tr.of("files.draftKitsDiffer"));
      history.reset(p);
      if (!deviceLink.session.connected()) deviceLink.baseline = null;
      file = imported ? null : chosen.toPath();
      focus.folder = "0";
      dirty = imported || deviceLink.session.connected();
      focus.selected = null;
      shell.render();
      notices.message(
          imported
              ? Tr.of("files.imported", chosen.getName(), p.nodes.size())
              : Tr.of("files.opened", chosen.getName()));
    } catch (Exception e) {
      notices.error(e);
    }
  }

  private FileChooser chooser() {
    FileChooser f = new FileChooser();
    f.getExtensionFilters()
        .add(
            new FileChooser.ExtensionFilter(Tr.of("files.projectType"), "*.sedona.json", "*.json"));
    return f;
  }

  private FileChooser.ExtensionFilter appFilter() {
    return new FileChooser.ExtensionFilter(Tr.of("files.appType"), "*.sax", "*.sab");
  }

  /**
   * Writes the draft as app.sax or app.sab; the project file and its unsaved state stay as they
   * are.
   */
  void exportApp() {
    FileChooser chooser = new FileChooser();
    chooser
        .getExtensionFilters()
        .addAll(
            new FileChooser.ExtensionFilter("app.sab", "*.sab"),
            new FileChooser.ExtensionFilter("app.sax", "*.sax"));
    chooser.setInitialFileName("app.sab");
    File chosen = chooser.showSaveDialog(shell.stage());
    if (chosen == null) return;
    try {
      java.nio.file.Path target = chosen.toPath();
      if (!AppFiles.isAppFile(target))
        target =
            target.resolveSibling(
                target.getFileName()
                    + (chooser.getSelectedExtensionFilter() != null
                            && chooser
                                .getSelectedExtensionFilter()
                                .getExtensions()
                                .contains("*.sax")
                        ? ".sax"
                        : ".sab"));
      List<String> defaulted = AppFiles.write(history.get(), target);
      String localAppearance = !history.get().captions.isEmpty() || history.get().nodes.values().stream().anyMatch(n -> !n.note.isBlank() || !n.icon.isBlank() || n.info || n.width > 0)
          ? "\n" + Tr.of("annotation.exportNote") : "";
      notices.message(
          (defaulted.isEmpty()
              ? Tr.of("files.exported", target)
              : Tr.of("files.exportedSecrets", target, String.join(", ", defaulted))) + localAppearance);
    } catch (Exception e) {
      notices.error(e);
    }
  }

  void save() {
    if (file == null) {
      FileChooser chooser = chooser();
      chooser.setInitialFileName("project.sedona.json");
      File chosen = chooser.showSaveDialog(shell.stage());
      if (chosen == null) return;
      file = chosen.toPath();
    }
    try {
      ProjectStore.save(history.get(), file);
      dirty = false;
      shell.title();
      notices.message(Tr.of("files.saved", file));
    } catch (Exception e) {
      notices.error(e);
    }
  }

  /** Puts the file association back after the draft was re-read from the simulator. */
  void reattach(java.nio.file.Path previousFile, boolean wasDirty) {
    file = previousFile;
    dirty = wasDirty;
  }

  /** The draft now comes from the device: no file behind it and nothing unsaved. */
  void detachFromFile() {
    dirty = false;
    file = null;
  }
}

package ru.bccontrol.sedonastudio.ui;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import ru.bccontrol.sedonastudio.model.*;
import ru.bccontrol.sedonastudio.storage.ProjectStore;

/** UI checks for original/fallback icons and local-only annotations. */
final class SelfTestAnnotations {
  static void run(StudioApp app) {
    try {
      String configured = System.getProperty("studio.test.icons", "");
      if (configured.isBlank()) {
        Path folder = Path.of(System.getProperty("studio.data"), "icon-fixture");
        Files.createDirectories(folder);
        Path jar = folder.resolve("icons-ux.jar");
        var bitmap =
            new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = bitmap.createGraphics();
        g.setColor(java.awt.Color.BLUE);
        g.fillOval(1, 1, 14, 14);
        g.dispose();
        var png = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(bitmap, "png", png);
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
          zip.putNextEntry(new ZipEntry("x16/control/util/sine.png"));
          zip.write(png.toByteArray());
          zip.closeEntry();
        }
        configured = jar.toString();
      }
      final String pack = configured;
      TypeIcons.configure(pack, true, true, () -> {});
      Project.Node probe = new Project.Node();
      probe.typeIcon = "module://icons/x16/control/util/sine.png";
      final int[] ticks = {0};
      final javafx.animation.Timeline[] wait = new javafx.animation.Timeline[1];
      wait[0] = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.millis(100), event -> {
        try {
          if (++ticks[0] > 600) throw new AssertionError("Icon preload or startup work timed out: " + TypeIcons.failure);
          if (app.deviceLink.busy.get() || !TypeIcons.hasOriginal(probe)) return;
          wait[0].stop();
          check(app, pack);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
      }));
      wait[0].setCycleCount(javafx.animation.Animation.INDEFINITE);wait[0].play();
    } catch (Throwable e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  private static void require(boolean ok, String reason) {
    if (!ok) throw new AssertionError(reason);
  }

  private static void check(StudioApp app, String pack) throws Exception {
    app.projectFiles.demo();
    Project project = app.history.get().copy();
    Project.Node n = project.nodes.get("demo0");
    n.typeIcon = "module://icons/x16/control/util/sine.png";
    n.icon = "!";
    n.note = "Instance-only comment";
    n.info = true;
    n.infoSource = "comment";
    Project.Caption caption = new Project.Caption();
    caption.id = "icon-note";
    caption.anchor = n.id;
    caption.text = "unique-annotation-search";
    caption.color = "blue";
    caption.x = 800;
    caption.y = 100;
    project.captions.add(caption);
    app.history.reset(project);
    app.focus.folder = "0";
    app.focus.selected = n.id;
    app.focus.selection.clear();
    app.focus.selection.add(n.id);
    app.typeCatalog.types.clear();
    app.typeCatalog.types.add(n.copy());
    app.render();
    app.root.applyCss();
    app.root.layout();
    require(
        TypeIcons.hasOriginal(n), "Original PNG loaded from local archive: " + TypeIcons.failure);
    require(
        app.world.lookup("#type-icon-demo0") instanceof ImageView,
        "Original icon displayed on canvas");
    require(
        ((Label) app.world.lookup("#icon-demo0")).getText().equals("!"),
        "Instance marker remains separate");
    require(
        ((Label) app.world.lookup("#info-demo0")).getText().equals(n.note),
        "Comment chosen explicitly");
    require(
        app.paletteDock.palette.lookupAll(".tree-cell").stream()
            .anyMatch(
                cell -> cell instanceof TreeCell<?> tc && tc.getGraphic() instanceof ImageView),
        "Palette displays original PNG");
    require(
        app.projectTree.tree.getRoot().getChildren().get(0).getGraphic() != null,
        "Program tree icon present");
    @SuppressWarnings("unchecked")
    ComboBox<Object> source =
        (ComboBox<Object>) app.inspectorPane.content.lookup("#block-info-source");
    require(
        source != null && source.getItems().size() == 2,
        "Inspector offers independent comment/type sources");
    source.getSelectionModel().select(1);
    require(
        app.history.get().nodes.get(n.id).infoSource.equals("type"),
        "Type description choice committed");
    var shown = app.world.lookup("#info-demo0");
    require(
        shown instanceof Label && !((Label) shown).getText().equals(n.note),
        "Type help does not replace stored comment");
    app.edit(p -> EditorCommands.move(p, Map.of(n.id, new double[] {n.x + 24, n.y + 24})));
    require(app.history.get().caption(caption.id).x == 824, "Attached note follows move");
    app.undo();
    require(app.history.get().caption(caption.id).x == 800, "Undo restores note position");
    String originalIcon = app.typeCatalog.types.get(0).typeIcon;
    app.typeCatalog.types.get(0).typeIcon = "module://icons/x16/control/math/add.png";
    app.editing.useKitBuild(n.kitPart);
    require(app.history.get().nodes.get(n.id).typeIcon.endsWith("/math/add.png"), "Updating a kit also updates its icon metadata");
    app.undo();
    app.typeCatalog.types.get(0).typeIcon = originalIcon;
    app.savedView.leftTabs.getSelectionModel().select(2);
    app.root.applyCss();
    app.root.layout();
    app.projectTree.projectSearch.setText("unique-annotation-search");
    var hits = (ListView<?>) app.scene.lookup("#annotation-search");
    require(hits != null && hits.getItems().size() == 1, "Canvas notes found in project search");
    app.savedView.leftTabs.getSelectionModel().select(0);
    String before = ProjectStore.encode(app.history.get());
    TypeIcons.configure(pack, false, false, app::render);
    require(
        app.world.lookup("#type-icon-demo0") == null
            && app.world.lookup("#caption-icon-note") == null,
        "View toggles hide icons and notes");
    require(
        before.equals(ProjectStore.encode(app.history.get())), "View options never alter project");
    TypeIcons.configure(pack, true, true, app::render);
    Project.Node unknown = n.copy();
    unknown.typeIcon = "module://icons/x16/missing.png";
    require(
        !(TypeIcons.graphic(unknown, 16) instanceof ImageView),
        "Missing resource uses built-in category icon");
    require(!app.world.captionBounds().isEmpty(), "Visible notes participate in view bounds");
    app.fitDiagram();
    app.root.applyCss();
    app.root.layout();
    String output = System.getProperty("studio.smokeOutput", "");
    if (!output.isBlank())
      javax.imageio.ImageIO.write(
          javafx.embed.swing.SwingFXUtils.fromFXImage(app.scene.snapshot(null), null),
          "png",
          new File(output));
    System.out.println(
        "PASS annotation UI: original PNG in canvas/tree/palette, separate marker, explicit"
            + " description/comment, attached note move/undo, search, view toggles, fallback and"
            + " unchanged draft");
    app.projectFiles.dirty = false;
    if (app.deviceLink.timer != null) app.deviceLink.timer.shutdownNow();
    app.deviceLink.io.shutdown();
    app.stage.close();
    Platform.exit();
  }
}

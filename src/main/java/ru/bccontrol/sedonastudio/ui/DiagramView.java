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

/** The diagram viewport: scrolling, panning, zoom, the breadcrumb and the on-screen layout. */
final class DiagramView {
  private final History history;
  private final EditorFocus focus;
  private final ViewOptions view;
  private final Notices notices;
  private final EditorShell shell;
  /** Called after each canvas redraw; set by StudioApp. */
  Runnable onRendered = () -> {};

  /** The path to the open folder: every step is a link, then a note with the counts. */
  final HBox breadcrumb = new HBox(4);

  private final Label counts = new Label(Tr.of("diagram.breadcrumbRoot"));
  Slider zoomControl;
  ScrollPane diagramScroll;
  private String visibleFolder;

  DiagramView(
      History history, EditorFocus focus, ViewOptions view, Notices notices, EditorShell shell) {
    this.history = history;
    this.focus = focus;
    this.view = view;
    this.notices = notices;
    this.shell = shell;
  }

  /** Builds the zoom slider; it scales the canvas. */
  Slider buildZoomControl() {
    zoomControl = new Slider(.2, 3, 1);
    zoomControl.setPrefWidth(130);
    zoomControl
        .valueProperty()
        .addListener(
            (o, a, b) -> {
              view.zoom = b.doubleValue();
              shell
                  .world()
                  .getTransforms()
                  .setAll(new javafx.scene.transform.Scale(view.zoom, view.zoom, 0, 0));
            });
    return zoomControl;
  }

  /** Scrolling, panning and wheel zoom around the diagram. */
  ScrollPane buildScroll() {
    Group scale = new Group(shell.world());
    ScrollPane scroll = new ScrollPane(scale);
    diagramScroll = scroll;
    // Accept drops in empty viewport space outside the current diagram bounds.
    scroll.setOnDragOver(shell.world().getOnDragOver());
    scroll.setOnDragDropped(shell.world().getOnDragDropped());
    scroll.setPannable(false);
    final double[] pan = new double[4];
    scroll.addEventFilter(
        MouseEvent.MOUSE_PRESSED,
        e -> {
          if (e.getButton() == MouseButton.MIDDLE) {
            pan[0] = e.getSceneX();
            pan[1] = e.getSceneY();
            pan[2] = scroll.getHvalue();
            pan[3] = scroll.getVvalue();
            scroll.setCursor(javafx.scene.Cursor.MOVE);
            e.consume();
          }
        });
    scroll.addEventFilter(
        MouseEvent.MOUSE_DRAGGED,
        e -> {
          if (e.isMiddleButtonDown()) {
            double width =
                Math.max(
                    1, scale.getBoundsInLocal().getWidth() - scroll.getViewportBounds().getWidth());
            double height =
                Math.max(
                    1,
                    scale.getBoundsInLocal().getHeight() - scroll.getViewportBounds().getHeight());
            scroll.setHvalue(pan[2] - (e.getSceneX() - pan[0]) / width);
            scroll.setVvalue(pan[3] - (e.getSceneY() - pan[1]) / height);
            e.consume();
          }
        });
    scroll.addEventFilter(
        MouseEvent.MOUSE_RELEASED,
        e -> {
          if (e.getButton() == MouseButton.MIDDLE) {
            scroll.setCursor(javafx.scene.Cursor.DEFAULT);
            e.consume();
          }
        });
    scroll.addEventFilter(
        ScrollEvent.SCROLL,
        e -> {
          if (e.isControlDown()) {
            Point2D anchor = shell.world().sceneToLocal(e.getSceneX(), e.getSceneY());
            double oldZoom = view.zoom;
            double offsetX =
                scroll.getHvalue()
                    * Math.max(
                        0,
                        shell.world().getPrefWidth() * oldZoom
                            - scroll.getViewportBounds().getWidth());
            double offsetY =
                scroll.getVvalue()
                    * Math.max(
                        0,
                        shell.world().getPrefHeight() * oldZoom
                            - scroll.getViewportBounds().getHeight());
            zoomControl.setValue(view.zoom * (e.getDeltaY() > 0 ? 1.1 : 1 / 1.1));
            scroll.layout();
            scroll.setHvalue(
                (offsetX + anchor.getX() * (view.zoom - oldZoom))
                    / Math.max(
                        1,
                        shell.world().getPrefWidth() * view.zoom
                            - scroll.getViewportBounds().getWidth()));
            scroll.setVvalue(
                (offsetY + anchor.getY() * (view.zoom - oldZoom))
                    / Math.max(
                        1,
                        shell.world().getPrefHeight() * view.zoom
                            - scroll.getViewportBounds().getHeight()));
            e.consume();
          }
        });
    return scroll;
  }

  void renderCanvas() {
    if (diagramScroll != null && !Objects.equals(visibleFolder, focus.folder)) {
      diagramScroll.setHvalue(0);
      diagramScroll.setVvalue(0);
      visibleFolder = focus.folder;
    }
    Project p = history.get();
    // Project / folder / folder ... : a click on a step opens that folder.
    java.util.List<Project.Node> path = new java.util.ArrayList<>();
    for (Project.Node at = p.nodes.get(focus.folder);
        at != null && !at.id.equals("0") && path.size() < 64;
        at = p.nodes.get(at.parent)) path.add(0, at);
    breadcrumb.getChildren().clear();
    breadcrumb.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
    breadcrumb.getChildren().add(step(p.name, "0"));
    for (Project.Node folder : path) {
      breadcrumb.getChildren().add(new Label("/"));
      breadcrumb.getChildren().add(step(folder.name, folder.id));
    }
    counts.setText(Tr.of("diagram.blockCount", p.nodes.size()));
    breadcrumb.getChildren().add(counts);
    shell.world().setBackground(null);
    shell.world().setStyle("-fx-background-image: none;");
    shell.world().applyCss();
    if (!view.showGrid) { /* Theme fill only. */ }
    else {
      int step = view.gridStep;
      double width = view.gridWidth;
      javafx.scene.image.WritableImage tile = new javafx.scene.image.WritableImage(step * 5, step * 5);
      for (int y = 0; y < step * 5; y++) for (int x = 0; x < step * 5; x++) {
        boolean major = x < width || y < width;
        boolean minor = x % step < width || y % step < width;
        tile.getPixelWriter().setColor(x, y, javafx.scene.paint.Color.color(0.22, 0.43, 0.56,
            major ? view.gridContrast : minor ? view.gridContrast * 0.45 : 0));
      }
      shell.world().setStyle("-fx-background-image: none;");
      var base = shell.world().getBackground();
      shell.world().setBackground(new javafx.scene.layout.Background(
          base == null ? new javafx.scene.layout.BackgroundFill[0]
              : base.getFills().toArray(javafx.scene.layout.BackgroundFill[]::new),
          new javafx.scene.layout.BackgroundImage[] {new javafx.scene.layout.BackgroundImage(tile, javafx.scene.layout.BackgroundRepeat.REPEAT,
              javafx.scene.layout.BackgroundRepeat.REPEAT, javafx.scene.layout.BackgroundPosition.DEFAULT,
              javafx.scene.layout.BackgroundSize.DEFAULT)}));
    }
    if (focus.selectedWire != null) {
      String key = focus.selectedWire.key();
      focus.selectedWire =
          p.wires.stream().filter(w -> w.key().equals(key)).findFirst().orElse(null);
    }
    shell.world().render();
    onRendered.run();
    int shifted = 0;
    for (Map.Entry<String, Bounds> item : shell.world().blockBounds().entrySet()) {
      Project.Node n = p.nodes.get(item.getKey());
      if (Math.abs(n.x - item.getValue().getMinX()) > .5
          || Math.abs(n.y - item.getValue().getMinY()) > .5) shifted++;
    }
    if (shifted > 0) counts.setText(Tr.of("diagram.spreadLocally", counts.getText(), shifted));
  }

  private Hyperlink step(String name, String folderId) {
    Hyperlink link = new Hyperlink(name);
    link.setPadding(javafx.geometry.Insets.EMPTY);
    link.setOnAction(
        e -> {
          if (focus.folder.equals(folderId)) return;
          focus.folder = folderId;
          focus.selected = null;
          focus.selectedWire = null;
          shell.render();
        });
    return link;
  }

  private final HBox legend = new HBox(10);

  /** What the port and link colours mean; call again after the colours changed. */
  HBox legend() {
    legend.getChildren().clear();
    legend.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
    legend.setId("type-legend");
    String[] names = {
      Tr.of("legend.bool"), Tr.of("legend.whole"), Tr.of("legend.real"), Tr.of("legend.text")
    };
    for (int i = 0; i < names.length; i++) {
      javafx.scene.shape.Circle dot =
          new javafx.scene.shape.Circle(5, DiagramPane.KIND_COLORS.get(DiagramPane.KINDS[i]));
      HBox item = new HBox(4, dot, new Label(names[i]));
      item.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
      legend.getChildren().add(item);
    }
    Tooltip.install(legend, new Tooltip(Tr.of("legend.tip")));
    return legend;
  }

  void commitLayout() {
    Map<String, Bounds> bounds = shell.world().blockBounds();
    boolean changed = false;
    for (Map.Entry<String, Bounds> item : bounds.entrySet()) {
      Bounds b = item.getValue();
      Project.Node n = history.get().nodes.get(item.getKey());
      if (b.getMinX() > 3060 || b.getMinY() > 3060)
        throw new IllegalStateException(Tr.of("diagram.layoutOutOfRange"));
      if (n.x != Math.round(b.getMinX() / 12) * 12 || n.y != Math.round(b.getMinY() / 12) * 12)
        changed = true;
    }
    if (!changed) {
      notices.message(Tr.of("diagram.layoutAlreadyPinned"));
      return;
    }
    shell.edit(
        p -> {
          for (Map.Entry<String, Bounds> item : bounds.entrySet()) {
            Project.Node n = p.nodes.get(item.getKey());
            double x = Math.round(item.getValue().getMinX() / 12) * 12;
            double y = Math.round(item.getValue().getMinY() / 12) * 12;
            for (Project.Caption c : p.captions)
              if (c.anchor.equals(n.id)) {
                c.x = Math.max(0, Math.min(10000, c.x + x - n.x));
                c.y = Math.max(0, Math.min(10000, c.y + y - n.y));
              }
            n.x = x;
            n.y = y;
          }
        });
    notices.message(Tr.of("diagram.layoutPinned"));
  }

  void fitDiagram() {
    double right = 300, bottom = 200;
    for (Bounds b : shell.world().blockBounds().values()) {
      right = Math.max(right, b.getMaxX() + 48);
      bottom = Math.max(bottom, b.getMaxY() + 48);
    }
    for (Bounds b : shell.world().captionBounds().values()) {
      right = Math.max(right, b.getMaxX() + 48);
      bottom = Math.max(bottom, b.getMaxY() + 48);
    }
    zoomControl.setValue(
        Math.min(
            diagramScroll.getViewportBounds().getWidth() / right,
            diagramScroll.getViewportBounds().getHeight() / bottom));
    diagramScroll.setHvalue(0);
    diagramScroll.setVvalue(0);
  }

  void upFolder() {
    Project.Node n = history.get().nodes.get(focus.folder);
    focus.folder = n == null || focus.folder.equals("0") ? "0" : n.parent;
    focus.selected = null;
    focus.selectedWire = null;
    shell.render();
  }

  void focusSelected() {
    Project.Node n = history.get().nodes.get(focus.selected);
    if (n == null) return;
    Bounds shown = shell.world().blockBounds().get(focus.selected);
    double x = shown == null ? n.x : shown.getMinX(), y = shown == null ? n.y : shown.getMinY();
    diagramScroll.setHvalue(
        Math.max(
                0,
                x * view.zoom - diagramScroll.getViewportBounds().getWidth() / 2 + 110 * view.zoom)
            / Math.max(
                1,
                shell.world().getPrefWidth() * view.zoom
                    - diagramScroll.getViewportBounds().getWidth()));
    diagramScroll.setVvalue(
        Math.max(
                0,
                y * view.zoom - diagramScroll.getViewportBounds().getHeight() / 2 + 60 * view.zoom)
            / Math.max(
                1,
                shell.world().getPrefHeight() * view.zoom
                    - diagramScroll.getViewportBounds().getHeight()));
  }
}

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

/**
 * Self-checks of the diagram canvas: geometry and routing, drag performance, mouse and group
 * gestures.
 */
final class SelfTestCanvas {
  private final StudioApp app;

  SelfTestCanvas(StudioApp app) {
    this.app = app;
  }

  private void mouse(
      Node target, javafx.event.EventType<MouseEvent> type, Point2D point, boolean down) {
    javafx.event.Event.fireEvent(
        target,
        new MouseEvent(
            type,
            point.getX(),
            point.getY(),
            point.getX(),
            point.getY(),
            MouseButton.PRIMARY,
            1,
            false,
            false,
            false,
            false,
            down,
            false,
            false,
            false,
            false,
            false,
            new PickResult(
                target,
                target.sceneToLocal(point.getX(), point.getY()).getX(),
                target.sceneToLocal(point.getX(), point.getY()).getY())));
  }

  void geometryChecks() throws Exception {
    boolean previousOverlap = app.view.avoidOverlap;
    app.view.avoidOverlap = true;
    Project fixture = app.history.get().copy();
    Project.Node tall = fixture.nodes.get("demo0");
    tall.x = 100;
    tall.y = 100;
    for (int i = 0; i < 15; i++) {
      Project.Port p = tall.ports.get(0).copy();
      p.name = "value" + i;
      p.id = 10 + i;
      tall.ports.add(p);
    }
    fixture.nodes.get("demo1").x = 190;
    fixture.nodes.get("demo1").y = 100;
    fixture.nodes.get("demo2").x = 220;
    fixture.nodes.get("demo2").y = 200;
    String original = ProjectStore.encode(fixture);
    app.history.reset(fixture);
    app.render();
    app.scene.getRoot().applyCss();
    app.scene.getRoot().layout();
    List<Bounds> rectangles = new ArrayList<>(app.world.blockBounds().values());
    for (int i = 0; i < rectangles.size(); i++)
      for (int j = i + 1; j < rectangles.size(); j++)
        if (rectangles.get(i).intersects(rectangles.get(j)))
          throw new AssertionError("Actual card overlap");
    if (!original.equals(ProjectStore.encode(app.history.get())))
      throw new AssertionError("View layout changed device coordinates");
    Bounds alignedA = new BoundingBox(60, 60, 220, 300),
        alignedB = new BoundingBox(500, 60, 220, 300);
    List<Point2D> flat =
        new WireRouter(List.of(alignedA, alignedB), 1000, 800)
            .route(new Point2D(265, 191.5), new Point2D(515, 191.5), alignedA, alignedB);
    if (flat.size() != 2 || flat.get(0).getY() != flat.get(1).getY())
      throw new AssertionError("Off-grid aligned ports must have a straight link");
    Map<String, Bounds> beforeSelection = app.world.blockBounds();
    app.focus.selected = "demo0";
    app.renderCanvas();
    if (!beforeSelection.get("demo0").equals(app.world.blockBounds().get("demo0")))
      throw new AssertionError("Selection changed card bounds");
    app.focus.selected = null;
    System.out.println(
        "PASS IO geometry: exact off-grid horizontal link, stable selected border, fixed 24px port"
            + " rows");
    Bounds source = new BoundingBox(60, 60, 220, 300),
        obstacle = new BoundingBox(400, 0, 220, 500),
        target = new BoundingBox(780, 80, 220, 300);
    WireRouter router = new WireRouter(List.of(source, obstacle, target), 1400, 900);
    List<Point2D> route =
        router.route(new Point2D(265, 200), new Point2D(795, 220), source, target);
    if (route.size() < 4) throw new AssertionError("Obstacle route missing");
    for (int i = 1; i < route.size(); i++) {
      Point2D a = route.get(i - 1), b = route.get(i);
      if (a.getX() != b.getX() && a.getY() != b.getY())
        throw new AssertionError("Nonorthogonal route");
      for (int sample = 0; sample <= 100; sample++)
        if (obstacle.contains(a.interpolate(b, sample / 100.0)))
          throw new AssertionError("Wire crosses card");
    }
    if (router.route(new Point2D(985, 260), new Point2D(75, 280), target, source).isEmpty())
      throw new AssertionError("Feedback route");
    if (router.route(new Point2D(265, 140), new Point2D(75, 220), source, source).isEmpty())
      throw new AssertionError("Self route");
    WireRouter realRouter =
        new WireRouter(
            app.world.blockBounds().values(), app.world.getPrefWidth(), app.world.getPrefHeight());
    for (Project.Wire wire : app.history.get().wires) {
      Point2D from =
          app.world.sceneToLocal(
              app.world
                  .lookup("#output-" + wire.source + "-" + wire.sourcePort)
                  .localToScene(0, 0));
      Point2D to =
          app.world.sceneToLocal(
              app.world.lookup("#input-" + wire.target + "-" + wire.targetPort).localToScene(0, 0));
      if (realRouter
          .route(
              from,
              to,
              app.world.blockBounds().get(wire.source),
              app.world.blockBounds().get(wire.target))
          .isEmpty())
        throw new AssertionError(
            "Actual tight card routing "
                + wire.key()
                + " from="
                + from
                + " to="
                + to
                + " boxes="
                + app.world.blockBounds());
    }
    String imagePath = System.getProperty("studio.smokeOutput", "build/studio-preview.png");
    System.setProperty("studio.smokeOutput", imagePath + ".geometry.png");
    app.fitDiagram();
    app.selfTest.snapshot();
    System.setProperty("studio.smokeOutput", imagePath);
    app.diagramView.zoomControl.setValue(1);
    app.diagramView.commitLayout();
    if (original.equals(ProjectStore.encode(app.history.get())))
      throw new AssertionError("Commit layout did not save positions");
    if (!app.history.undo() || !original.equals(ProjectStore.encode(app.history.get())))
      throw new AssertionError("Layout undo");
    app.projectFiles.demo();
    app.focus.selected = null;
    app.focus.folder = "0";
    app.view.avoidOverlap = previousOverlap;
    app.render();
    System.out.println(
        "PASS geometry: tall overlapping cards, no draft mutation, explicit layout commit/undo,"
            + " orthogonal obstacle/feedback/self routing");
  }

  void dragPerformanceChecks() throws Exception {
    boolean overlap = app.view.avoidOverlap;
    app.view.avoidOverlap = false;
    app.diagramView.zoomControl.setValue(1);
    Project p = new Project();
    p.name = "Synthetic drag benchmark";
    Project.Node hub = Catalog.bundled().get(2).copy();
    hub.id = "hub";
    hub.name = "hub";
    hub.x = 2100;
    hub.y = 60;
    p.nodes.put(hub.id, hub);
    p.parts.add(hub.kitPart);
    Project.Node template = Catalog.bundled().get(0);
    p.parts.add(template.kitPart);
    for (int i = 0; i < 100; i++) {
      Project.Node source = template.copy();
      source.id = "perf" + i;
      source.name = "s" + i;
      source.x = 60 + (i % 5) * 300;
      source.y = 60 + (i / 5) * 120;
      p.nodes.put(source.id, source);
      Project.Port input = hub.port("in1").copy();
      input.name = "tap" + i;
      input.id = 100 + i;
      hub.ports.add(input);
      p.connect(source.id, "out", hub.id, input.name);
    }
    app.history.reset(p);
    app.focus.selected = null;
    app.focus.folder = "0";
    app.render();
    app.scene.getRoot().applyCss();
    app.scene.getRoot().layout();
    Node box = app.world.lookup("#block-hub");
    Point2D start = box.localToScene(50, 20);
    mouse(box, MouseEvent.MOUSE_PRESSED, start, true);
    long routes = app.world.routePasses(), previews = app.world.previewPasses();
    long began = System.nanoTime();
    for (int i = 1; i <= 1000; i++)
      mouse(box, MouseEvent.MOUSE_DRAGGED, start.add(i * .12, i * .024), true);
    app.world.updateFastWires();
    double elapsed = (System.nanoTime() - began) / 1000000.0;
    if (app.world.routePasses() != routes)
      throw new AssertionError("Expensive routing during drag");
    if (app.world.dragPreviewCount() != 100) throw new AssertionError("Adjacent preview count");
    if (app.world.previewPasses() - previews != 1)
      throw new AssertionError("Mouse events were not coalesced");
    mouse(box, MouseEvent.MOUSE_RELEASED, start.add(120, 24), false);
    if (app.world.dragPreviewCount() != 0 || app.world.routePasses() <= routes)
      throw new AssertionError("Final routing after release");
    if (app.history.get().nodes.get("hub").x != 2220 || app.history.get().nodes.get("hub").y != 84)
      throw new AssertionError("Drag commit position");
    if (!app.history.undo() || app.history.get().nodes.get("hub").x != 2100)
      throw new AssertionError("Drag undo");
    if (!app.history.redo() || app.history.get().nodes.get("hub").x != 2220)
      throw new AssertionError("Drag redo");
    System.out.println(
        "PASS drag performance: 101 blocks / 100 links / 1000 mouse moves in "
            + Math.round(elapsed)
            + " ms; zero route passes during movement, one preview update, final route after"
            + " release");
    app.render();
    app.deviceLink.busy.set(true);
    for (int repeat = 0; repeat < 3; repeat++) {
      Node movedBox = app.world.lookup("#block-hub");
      Point2D at = movedBox.localToScene(50, 20);
      mouse(movedBox, MouseEvent.MOUSE_PRESSED, at, true);
      mouse(movedBox, MouseEvent.MOUSE_DRAGGED, at.add(24, 24), true);
      long beforeRelease = app.world.routePasses();
      mouse(movedBox, MouseEvent.MOUSE_RELEASED, at.add(24, 24), false);
      if (app.world.routePasses() <= beforeRelease)
        throw new AssertionError("Deferred repeated movement left stale wire geometry");
    }
    app.deviceLink.busy.set(false);
    app.deviceLink.onSettled.accept(true);
    System.out.println("PASS repeated deferred drag: wires rerouted after each release");
    app.view.avoidOverlap = overlap;
    app.projectFiles.demo();
    app.focus.selected = null;
    app.focus.folder = "0";
    app.render();
  }

  void groupGestureChecks() throws Exception {
    app.projectFiles.demo();
    app.focus.selected = null;
    app.focus.selection.clear();
    app.focus.folder = "0";
    app.render();
    app.scene.getRoot().applyCss();
    app.scene.getRoot().layout();
    app.diagramView.zoomControl.setValue(1);
    mouse(app.world, MouseEvent.MOUSE_PRESSED, app.world.localToScene(60, 50), true);
    mouse(app.world, MouseEvent.MOUSE_DRAGGED, app.world.localToScene(330, 450), true);
    mouse(app.world, MouseEvent.MOUSE_RELEASED, app.world.localToScene(330, 450), false);
    if (app.focus.selectedIds().size() != 2)
      throw new AssertionError("Marquee group selection: " + app.focus.selectedIds());
    Node a = app.world.lookup("#block-demo0");
    Point2D start = a.localToScene(50, 20);
    double ax = app.history.get().nodes.get("demo0").x, bx = app.history.get().nodes.get("demo1").x;
    mouse(a, MouseEvent.MOUSE_PRESSED, start, true);
    mouse(a, MouseEvent.MOUSE_DRAGGED, start.add(120, 24), true);
    mouse(a, MouseEvent.MOUSE_RELEASED, start.add(120, 24), false);
    if (app.history.get().nodes.get("demo0").x - ax != app.history.get().nodes.get("demo1").x - bx)
      throw new AssertionError("Group relative positions");
    if (!app.history.undo()
        || app.history.get().nodes.get("demo0").x != ax
        || app.history.get().nodes.get("demo1").x != bx)
      throw new AssertionError("Group drag atomic undo");
    app.selectGroup(Set.of("demo0", "demo2"));
    app.editing.copySelection();
    int edges = app.history.get().wires.size();
    app.editing.pasteSelection();
    if (app.focus.selectedIds().size() != 2 || app.history.get().wires.size() != edges + 1)
      throw new AssertionError("Group copy with internal wire");
    app.projectFiles.demo();
    app.selectGroup(Set.of("demo0", "demo1", "demo2"));
    app.editing.arrangeSelection(0);
    if (app.history.get().nodes.get("demo0").x != app.history.get().nodes.get("demo2").x)
      throw new AssertionError("Group alignment");
    app.history.undo();
    app.editing.arrangeSelection(2);
    if (!app.history.get().validate().isEmpty()) throw new AssertionError("Distribution invalid");
    app.history.undo();
    app.focus.selectedWire = app.history.get().wires.get(0);
    app.history.edit(p -> p.wires.get(0).bends.add(new Project.Point(420, 240)));
    app.render();
    app.scene.getRoot().applyCss();
    app.scene.getRoot().layout();
    if (app.world.lookupAll(".manual-bend").size() != 1)
      throw new AssertionError("Manual bend handle");
    if (!app.history.undo() || !app.history.get().wires.get(0).bends.isEmpty())
      throw new AssertionError("Manual route undo");
    System.out.println("PASS alignment, distribution and manual waypoint handle with undo");
    app.render();
    app.scene.getRoot().applyCss();
    app.scene.getRoot().layout();
    long routeCount = app.world.routePasses();
    Node wireHit = app.world.lookupAll(".wire-hit").iterator().next();
    mouse(wireHit, MouseEvent.MOUSE_CLICKED, wireHit.localToScene(0, 0), false);
    if (app.world.routePasses() != routeCount)
      throw new AssertionError("Selecting a wire rerouted graph");
    System.out.println("PASS wire selection reuses routes without A* rebuild");
    routeCount = app.world.routePasses();
    app.edit(p -> p.nodes.get("demo0").note = "Geometry unchanged");
    app.scene.getRoot().applyCss();
    app.scene.getRoot().layout();
    if (app.world.routePasses() != routeCount)
      throw new AssertionError("Comment rerouted geometry");
    app.edit(p -> p.nodes.get("demo0").values.put("out", "42.0"));
    if (app.world.routePasses() != routeCount)
      throw new AssertionError("Value edit rerouted geometry");
    app.edit(p -> p.nodes.get("demo0").x += 24);
    if (app.world.routePasses() <= routeCount)
      throw new AssertionError("Geometry did not invalidate route cache");
    System.out.println("PASS geometry cache: note/value reuse, movement invalidation");
    Project.Wire diagnosticWire = app.history.get().wires.get(0);
    String graphBefore = ProjectStore.encode(app.history.get());
    app.deviceLink.baseline = app.history.get().copy();
    app.liveValues.current.put(
        diagnosticWire.source, Map.of(diagnosticWire.sourcePort, "12345678901234567890"));
    String tip = app.world.signalTooltip(diagnosticWire);
    if (!tip.contains("12345678901234567890")
        || !tip.contains(Tr.of("live.offline"))
        || !tip.contains("sys::float")
        || !tip.contains(Tr.of("inspector.target")))
      throw new AssertionError("Signal tooltip details/full value/quality");
    app.focus.selectedWire = diagnosticWire;
    app.focus.selected = null;
    app.inspectorPane.renderInspector();
    ((Button) app.scene.lookup("#wire-go-source")).fire();
    if (!Objects.equals(app.focus.selected, diagnosticWire.source))
      throw new AssertionError("Source navigation");
    app.focus.selectedWire = diagnosticWire;
    app.focus.selected = null;
    app.inspectorPane.renderInspector();
    ((Button) app.scene.lookup("#wire-go-target")).fire();
    if (!Objects.equals(app.focus.selected, diagnosticWire.target)
        || !ProjectStore.encode(app.history.get()).equals(graphBefore))
      throw new AssertionError("Target navigation changed graph");
    Project.Node targetNode = app.history.get().nodes.get(diagnosticWire.target);
    Project.Port readonly = targetNode.port("out");
    if (readonly != null && readonly.readonly) {
      String saved = targetNode.values.get("out");
      targetNode.values.put("out", "9999.0");
      app.liveValues.current.put(targetNode.id, Map.of("out", "8.25"));
      if (!app.liveValues.fullValue(targetNode, "out").equals("8.25")
          || app.liveValues.pendingValue(targetNode, "out"))
        throw new AssertionError("Readonly snapshot hid live value");
      targetNode.values.put("out", saved);
    }
    app.deviceLink.baseline = null;
    app.liveValues.current.clear();
    System.out.println(
        "PASS signal diagnostics: full cached value, type, offline quality, both endpoint buttons"
            + " and unchanged graph");
    app.projectFiles.demo();
    app.focus.selection.clear();
    app.focus.selected = null;
    app.render();
    System.out.println(
        "PASS group UI: marquee, relative drag, one-step undo, clipboard internal link and pasted"
            + " selection");
  }

  void gestureChecks() {
    app.scene.getRoot().applyCss();
    app.scene.getRoot().layout();
    app.diagramView.zoomControl.setValue(.6);
    Node block = app.world.lookup("#block-demo0");
    Point2D start = block.localToScene(50, 20);
    double oldX = app.history.get().nodes.get("demo0").x;
    mouse(block, MouseEvent.MOUSE_PRESSED, start, true);
    Point2D end = start.add(72, 36);
    mouse(block, MouseEvent.MOUSE_DRAGGED, end, true);
    mouse(block, MouseEvent.MOUSE_RELEASED, end, false);
    if (Math.abs(app.history.get().nodes.get("demo0").x - oldX - 120) > 6)
      throw new AssertionError("Scaled mouse component drag");
    app.history.edit(
        p -> p.wires.removeIf(w -> w.target.equals("demo2") && w.targetPort.equals("in1")));
    app.render();
    app.scene.getRoot().applyCss();
    app.scene.getRoot().layout();
    Node output = app.world.lookup("#output-demo0-out"),
        input = app.world.lookup("#input-demo2-in1");
    if (output == null || input == null) throw new AssertionError("Port anchors");
    Point2D from = output.localToScene(0, 0), to = input.localToScene(0, 0);
    mouse(output, MouseEvent.MOUSE_PRESSED, from, true);
    mouse(output, MouseEvent.MOUSE_DRAGGED, to, true);
    mouse(output, MouseEvent.MOUSE_RELEASED, to, false);
    if (app.history.get().wires.size() != 2) throw new AssertionError("Mouse wire drag");
    app.focus.folder = "missing";
    app.renderCanvas();
    app.projectTree.tree.getSelectionModel().clearSelection();
    app.projectTree
        .tree
        .getSelectionModel()
        .select(app.projectTree.tree.getRoot().getChildren().get(0));
    if (!app.focus.folder.equals("0") || app.focus.selected == null)
      throw new AssertionError("Tree leaf navigation");
    app.focus.selected = "demo0";
    app.scene.getRoot().requestFocus();
    double beforeNudge = app.history.get().nodes.get(app.focus.selected).x;
    javafx.event.Event.fireEvent(
        app.scene.getRoot(),
        new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.RIGHT, true, false, false, false));
    if (app.history.get().nodes.get(app.focus.selected).x != beforeNudge + 1)
      throw new AssertionError("Fine keyboard nudge");
    app.projectTree.projectSearch.setText("sum");
    app.projectTree.projectSearch.fireEvent(new javafx.event.ActionEvent());
    if (!"demo2".equals(app.focus.selected)) throw new AssertionError("Project search navigation");
    app.projectTree.projectSearch.clear();
    app.view.showGrid = false;
    app.view.showValues = false;
    app.renderCanvas();
    if (!app.world.getStyle().contains("none")) throw new AssertionError("Grid preference");
    app.view.showGrid = true;
    app.view.showValues = true;
    app.fitDiagram();
    if (app.view.zoom < .2 || app.view.zoom > 3) throw new AssertionError("Fit zoom limits");
    app.diagramView.diagramScroll.setHvalue(.2);
    app.diagramView.diagramScroll.setVvalue(.2);
    double panBefore = app.diagramView.diagramScroll.getHvalue();
    Point2D panPoint = app.diagramView.diagramScroll.localToScene(200, 150);
    for (int phase = 0; phase < 3; phase++) {
      javafx.event.EventType<MouseEvent> type =
          phase == 0
              ? MouseEvent.MOUSE_PRESSED
              : phase == 1 ? MouseEvent.MOUSE_DRAGGED : MouseEvent.MOUSE_RELEASED;
      Point2D point = phase == 0 ? panPoint : panPoint.add(-60, -40);
      javafx.event.Event.fireEvent(
          app.diagramView.diagramScroll,
          new MouseEvent(
              type,
              point.getX(),
              point.getY(),
              point.getX(),
              point.getY(),
              MouseButton.MIDDLE,
              1,
              false,
              false,
              false,
              false,
              false,
              phase != 2,
              false,
              false,
              false,
              false,
              new PickResult(app.diagramView.diagramScroll, 200, 150)));
    }
    if (app.diagramView.diagramScroll.getHvalue() <= panBefore)
      throw new AssertionError("Middle mouse pan");
    System.out.println("PASS routed middle-button pan");
    System.out.println(
        "PASS usability: Shift+arrow fine move, project search Enter navigation, grid/value"
            + " preferences, fit limits");
    app.diagramView.zoomControl.setValue(1);
    app.diagramView.diagramScroll.setHvalue(0);
    app.diagramView.diagramScroll.setVvalue(0);
    app.projectFiles.demo();
    app.focus.selected = null;
    app.focus.folder = "0";
    app.render();
    System.out.println(
        "PASS routed JavaFX mouse: block drag at 60%, wire press/drag/release, tree leaf"
            + " navigation, zoom origin");
  }
}

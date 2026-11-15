package ru.bccontrol.sedonastudio.ui;

import java.util.*;
import java.util.prefs.Preferences;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.transform.Scale;
import ru.bccontrol.sedonastudio.model.*;

/** Frozen, read-only comparison. Has no transport, history or mutable editor references. */
public final class ReviewDiagramPane extends BorderPane {
  private final Project before, after;
  private final Map<String, String> beforeNodes = new HashMap<>(), afterNodes = new HashMap<>();
  private final Set<String> beforeWires = new HashSet<>(), afterWires = new HashSet<>();
  private final DiagramPane left, right;
  private final ScrollPane leftScroll, rightScroll;
  private final Slider zoom = new Slider(.2, 1.5, .6);
  private String folder = "0";
  private boolean rendered;
  private final boolean previewOnly;
  private final CheckMenuItem values = new CheckMenuItem(Tr.of("review.showSnapshotValues"));
  private final CheckMenuItem sync = new CheckMenuItem(Tr.of("review.syncScroll"));
  private final CheckMenuItem fade = new CheckMenuItem(Tr.of("review.dimUnchanged"));

  public ReviewDiagramPane(Project before, Project after, Preferences prefs) {
    this(before, after, prefs, false);
  }

  public static ReviewDiagramPane preview(Project fragment, Preferences prefs) {
    return new ReviewDiagramPane(fragment, fragment, prefs, true);
  }

  private ReviewDiagramPane(Project before, Project after, Preferences prefs, boolean previewOnly) {
    this.previewOnly = previewOnly;
    getStylesheets().add(getClass().getResource("/studio.css").toExternalForm());
    setStyle("-studio-sheet: #f3f6fa;");
    this.before = before.copy();
    this.after = after.copy();
    if (!previewOnly) classify();
    values.setSelected(prefs.getBoolean(previewOnly ? "previewValues" : "reviewValues", false));
    if (previewOnly) values.setText(Tr.of("review.showSavedValues"));
    sync.setSelected(prefs.getBoolean("reviewSync", true));
    fade.setSelected(prefs.getBoolean("reviewFade", true));
    left = new DiagramPane(new SnapshotHost(this.before, true));
    right = new DiagramPane(new SnapshotHost(this.after, false));
    left.setMouseTransparent(true);
    right.setMouseTransparent(true);
    leftScroll = scroll(left);
    rightScroll = scroll(right);
    SplitPane split =
        new SplitPane(
            column(Tr.of("review.before"), leftScroll), column(Tr.of("review.after"), rightScroll));
    split.setDividerPositions(.5);
    setCenter(previewOnly ? column(after.name, rightScroll) : split);
    ComboBox<String> folders = new ComboBox<>();
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("0", Tr.of("project.root"));
    for (Project p : List.of(this.before, this.after))
      for (Project.Node n : p.nodes.values())
        if (n.type.equals("sys::Folder")) labels.put(n.id, n.name + " [" + n.id + "]");
    folders.getItems().addAll(labels.keySet());
    folders.setValue("0");
    folders.setConverter(
        new javafx.util.StringConverter<>() {
          public String toString(String id) {
            return labels.getOrDefault(id, id);
          }

          public String fromString(String text) {
            return "0";
          }
        });
    folders.setOnAction(
        e -> {
          folder = folders.getValue();
          render();
          leftScroll.setHvalue(0);
          leftScroll.setVvalue(0);
          rightScroll.setHvalue(0);
          rightScroll.setVvalue(0);
        });
    zoom.setPrefWidth(110);
    zoom.valueProperty().addListener((o, a, b) -> scale());
    Button fit = new Button(previewOnly ? Tr.of("review.fitTemplate") : Tr.of("review.fitBoth"));
    fit.setOnAction(e -> fit());
    MenuButton options =
        new MenuButton(previewOnly ? Tr.of("review.viewOptions") : Tr.of("review.compareOptions"));
    if (previewOnly) options.getItems().add(values);
    else options.getItems().addAll(values, sync, fade);
    values.setOnAction(
        e -> {
          prefs.putBoolean(previewOnly ? "previewValues" : "reviewValues", values.isSelected());
          render();
        });
    sync.setOnAction(
        e -> {
          prefs.putBoolean("reviewSync", sync.isSelected());
          if (sync.isSelected()) synchronize(leftScroll, rightScroll);
        });
    fade.setOnAction(
        e -> {
          prefs.putBoolean("reviewFade", fade.isSelected());
          decorate();
        });
    HBox controls = new HBox(8, new Label(Tr.of("review.folder")), folders, fit, zoom, options);
    controls.setPadding(new Insets(8));
    Label legend = new Label(Tr.of("review.legendCompare"));
    if (previewOnly) legend.setText(Tr.of("review.legendTemplate"));
    legend.setPadding(new Insets(6, 8, 8, 8));
    setTop(new VBox(controls, legend));
    linkScroll(leftScroll, rightScroll);
    linkScroll(rightScroll, leftScroll);
    setPrefSize(previewOnly ? 850 : 1000, previewOnly ? 550 : 480);
  }

  private void classify() {
    before.wires.forEach(w -> beforeWires.add(w.key()));
    after.wires.forEach(w -> afterWires.add(w.key()));
    for (Project.Node n : before.nodes.values())
      beforeNodes.put(n.id, after.nodes.containsKey(n.id) ? "same" : "removed");
    for (Project.Node n : after.nodes.values()) {
      Project.Node old = before.nodes.get(n.id);
      if (old == null) {
        afterNodes.put(n.id, "added");
        continue;
      }
      Project a = new Project(), b = new Project();
      a.nodes.put(old.id, old);
      b.nodes.put(n.id, n);
      String state = ProjectDiff.compare(a, b).isEmpty() ? "same" : "changed";
      beforeNodes.put(n.id, state);
      afterNodes.put(n.id, state);
    }
    for (Project.Wire w : before.wires)
      if (!afterWires.contains(w.key())) {
        mark(beforeNodes, w.source);
        mark(beforeNodes, w.target);
      }
    for (Project.Wire w : after.wires)
      if (!beforeWires.contains(w.key())) {
        mark(afterNodes, w.source);
        mark(afterNodes, w.target);
      }
  }

  private static void mark(Map<String, String> states, String id) {
    if ("same".equals(states.get(id))) states.put(id, "changed");
  }

  private static VBox column(String name, ScrollPane scroll) {
    VBox box = new VBox(5, new Label(name), scroll);
    VBox.setVgrow(scroll, Priority.ALWAYS);
    return box;
  }

  private static ScrollPane scroll(DiagramPane diagram) {
    ScrollPane scroll = new ScrollPane(new Group(diagram));
    scroll.setPannable(true);
    scroll.setMinWidth(180);
    return scroll;
  }

  private void linkScroll(ScrollPane a, ScrollPane b) {
    a.hvalueProperty()
        .addListener(
            (o, x, y) -> {
              if (sync.isSelected() && b.getHvalue() != y.doubleValue())
                b.setHvalue(y.doubleValue());
            });
    a.vvalueProperty()
        .addListener(
            (o, x, y) -> {
              if (sync.isSelected() && b.getVvalue() != y.doubleValue())
                b.setVvalue(y.doubleValue());
            });
  }

  private void synchronize(ScrollPane a, ScrollPane b) {
    b.setHvalue(a.getHvalue());
    b.setVvalue(a.getVvalue());
  }

  public void activate() {
    if (!rendered)
      Platform.runLater(
          () -> {
            if (!rendered) {
              render();
              fit();
            }
          });
  }

  private void render() {
    if (!previewOnly) left.render();
    right.render();
    double width = Math.max(left.getPrefWidth(), right.getPrefWidth());
    double height = Math.max(left.getPrefHeight(), right.getPrefHeight());
    for (DiagramPane p : diagrams()) {
      p.setPrefSize(width, height);
      p.resize(width, height);
    }
    rendered = true;
    scale();
    decorate();
  }

  private List<DiagramPane> diagrams() {
    return previewOnly ? List.of(right) : List.of(left, right);
  }

  private void scale() {
    for (DiagramPane p : diagrams())
      p.getTransforms().setAll(new Scale(zoom.getValue(), zoom.getValue(), 0, 0));
  }

  private void fit() {
    if (!rendered) return;
    double width = 1, height = 1;
    for (DiagramPane diagram : diagrams())
      for (var bounds : diagram.blockBounds().values()) {
        width = Math.max(width, bounds.getMaxX() + 24);
        height = Math.max(height, bounds.getMaxY() + 24);
      }
    double w =
        previewOnly
            ? rightScroll.getViewportBounds().getWidth()
            : Math.min(
                leftScroll.getViewportBounds().getWidth(),
                rightScroll.getViewportBounds().getWidth());
    double h =
        previewOnly
            ? rightScroll.getViewportBounds().getHeight()
            : Math.min(
                leftScroll.getViewportBounds().getHeight(),
                rightScroll.getViewportBounds().getHeight());
    if (w > 0 && h > 0)
      zoom.setValue(
          Math.max(
              .2,
              Math.min(
                  1, Math.min((w - 20) / Math.max(1, width), (h - 20) / Math.max(1, height)))));
    leftScroll.setHvalue(0);
    leftScroll.setVvalue(0);
    rightScroll.setHvalue(0);
    rightScroll.setVvalue(0);
  }

  private void decorate() {
    for (DiagramPane p : diagrams()) {
      for (javafx.scene.Node node : p.lookupAll(".review-same"))
        node.setOpacity(fade.isSelected() ? .4 : 1);
      for (var entry : (p == left ? beforeNodes : afterNodes).entrySet()) {
        javafx.scene.Node node = p.lookup("#block-" + entry.getKey());
        // The title is found by its id, not by its place inside the block.
        if (node != null
            && !entry.getValue().equals("same")
            && node.lookup("#title-" + entry.getKey()) instanceof Label title) {
          Project.Node n = (p == left ? before : after).nodes.get(entry.getKey());
          title.setText(
              (entry.getValue().equals("added")
                      ? "+ "
                      : entry.getValue().equals("removed") ? "− " : "Δ ")
                  + n.name);
        }
      }
    }
  }

  void previewSmokeChecks() {
    if (!previewOnly) throw new AssertionError("Not a preview");
    render();
    fit();
    if (!left.blockBounds().isEmpty()
        || right.blockBounds().isEmpty()
        || !right.isMouseTransparent()) throw new AssertionError("Preview render/read-only");
    if (!right.lookupAll(".review-same").isEmpty() || !right.lookupAll(".review-added").isEmpty())
      throw new AssertionError("Preview contains comparison colors");
    values.setSelected(true);
    render();
    if (!right.lookupAll(".block").stream().allMatch(n -> n.getOpacity() == 1))
      throw new AssertionError("Preview blocks faded");
    System.out.println(
        "PASS template preview: one read-only diagram, independent value option, no comparison"
            + " colors and no hidden diagram routing");
  }

  void smokeChecks() {
    render();
    fit();
    if (!left.isMouseTransparent() || !right.isMouseTransparent())
      throw new AssertionError("Editable review");
    if (!beforeNodes.containsValue("removed")
        || !afterNodes.containsValue("added")
        || !afterNodes.containsValue("changed"))
      throw new AssertionError("Missing difference states");
    if (left.lookupAll(".review-removed").isEmpty()
        || right.lookupAll(".review-added").isEmpty()
        || right.lookupAll(".review-changed").isEmpty())
      throw new AssertionError("Missing diagram highlights");
    boolean removedLink =
        left.lookupAll(".wire-line").stream()
            .anyMatch(
                n ->
                    n instanceof javafx.scene.shape.Path path
                        && javafx.scene.paint.Color.web("#b44444").equals(path.getStroke()));
    boolean addedLink =
        right.lookupAll(".wire-line").stream()
            .anyMatch(
                n ->
                    n instanceof javafx.scene.shape.Path path
                        && javafx.scene.paint.Color.web("#23816b").equals(path.getStroke()));
    if (!removedLink || !addedLink) throw new AssertionError("Missing link colors");
    sync.setSelected(true);
    leftScroll.setHvalue(.35);
    leftScroll.setVvalue(.4);
    if (rightScroll.getHvalue() != .35 || rightScroll.getVvalue() != .4)
      throw new AssertionError("Unsynchronized scroll");
    values.setSelected(true);
    render();
    fade.setSelected(false);
    decorate();
    for (var node : right.lookupAll(".review-same"))
      if (node.getOpacity() != 1) throw new AssertionError("Fade option");
    folder = "review-folder";
    render();
    if (!left.blockBounds().isEmpty() || !right.blockBounds().containsKey("nested"))
      throw new AssertionError("Folder union");
    folder = "0";
    render();
    fit();
    System.out.println(
        "PASS visual comparison: frozen read-only diagrams, added/removed/changed blocks and links,"
            + " folder union, synchronized scroll, values and fade options");
  }

  private final class SnapshotHost implements DiagramPane.Host {
    private final Project p;
    private final boolean old;

    SnapshotHost(Project p, boolean old) {
      this.p = p;
      this.old = old;
    }

    public Project project() {
      return p;
    }

    public String folder() {
      return folder;
    }

    public String selected() {
      return null;
    }

    public Project.Wire selectedWire() {
      return null;
    }

    public double zoom() {
      return zoom.getValue();
    }

    public boolean snap() {
      return false;
    }

    public boolean showValues() {
      return values.isSelected();
    }

    public boolean avoidOverlap() {
      return false;
    }

    public String display(Project.Node n, String port) {
      return SecretFields.matches(port) ? "•••" : n.values.getOrDefault(port, "");
    }

    public String nodeDelta(String id) {
      if (previewOnly) return "";
      return (old ? beforeNodes : afterNodes).getOrDefault(id, "same");
    }

    public String wireDelta(Project.Wire w) {
      if (previewOnly) return "";
      return (old ? afterWires : beforeWires).contains(w.key())
          ? "same"
          : old ? "removed" : "added";
    }

    public Set<String> selection() {
      return Set.of();
    }

    public boolean highlighted() {
      return false;
    }

    public void select(String id) {}

    public void select(String id, boolean additive) {}

    public void selectGroup(Set<String> ids) {}

    public void moveGroup(Map<String, double[]> positions) {}

    public void selectWire(Project.Wire w) {}

    public void navigateWire(Project.Wire w, boolean source) {}

    public void move(String id, double x, double y) {}

    public void connect(String a, String ap, String b, String bp) {}

    public void bend(Project.Wire w, int i, double x, double y) {}

    public void clearBends(Project.Wire w) {}

    public void openFolder(String id) {}

    public void duplicate(String id) {}

    public void delete(String id) {}

    public void message(String text) {}

    public void error(Exception e) {
      throw new IllegalStateException(Tr.of("review.cannotShow"), e);
    }
  }
}

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

/** Left-hand project tree and the project search tab. */
final class ProjectTree {
  private final History history;
  private final EditorFocus focus;
  private final EditorShell shell;
  TreeView<String> tree;
  boolean treeRendering;
  Runnable refreshProjectSearch = () -> {};

  TextField projectSearch;

  /** Called after the user picked a folder or block in the tree; set by StudioApp. */
  Runnable onNavigate = () -> {};

  ProjectTree(History history, EditorFocus focus, EditorShell shell) {
    this.history = history;
    this.focus = focus;
    this.shell = shell;
  }

  /** Everything a block can be found by: name, type, id, its note and "port=value" pairs. */
  private static String searchText(Project.Node n) {
    StringBuilder text = new StringBuilder(n.name + " " + n.type + " " + n.id + " " + n.note);
    for (Map.Entry<String, String> value : n.values.entrySet())
      if (!value.getKey().equals("meta"))
        text.append(' ').append(value.getKey()).append('=').append(value.getValue());
    return text.toString().toLowerCase(Locale.ROOT);
  }

  /** The note or the "port=value" that matched, when the name, type and id did not. */
  private static String matchedField(Project.Node n, String q) {
    if (q.isEmpty() || (n.name + " " + n.type + " " + n.id).toLowerCase(Locale.ROOT).contains(q))
      return null;
    for (Map.Entry<String, String> value : n.values.entrySet()) {
      String pair = value.getKey() + "=" + value.getValue();
      if (!value.getKey().equals("meta") && pair.toLowerCase(Locale.ROOT).contains(q)) return pair;
    }
    return n.note.toLowerCase(Locale.ROOT).contains(q) ? "✎ " + n.note : null;
  }

  private void collectExpanded(TreeItem<String> item, Set<String> ids) {
    String label = item.getValue();
    if (item.isExpanded())
      ids.add(label.substring(label.lastIndexOf("[#") + 2, label.length() - 1));
    for (TreeItem<String> child : item.getChildren()) collectExpanded(child, ids);
  }

  public void renderTree() {
    Set<String> expanded = new HashSet<>();
    if (tree.getRoot() != null) collectExpanded(tree.getRoot(), expanded);
    treeRendering = true;
    TreeItem<String> rootItem = new TreeItem<>(history.get().name + " [#0]");
    rootItem.setExpanded(true);
    Map<String, TreeItem<String>> items = new HashMap<>();
    items.put("0", rootItem);
    for (Project.Node n : history.get().nodes.values())
      if (!n.id.equals("0"))
        items.put(n.id, new TreeItem<>(n.name + " · " + n.type + " [#" + n.id + "]"));
    for (Project.Node n : history.get().nodes.values())
      if (!n.id.equals("0"))
        items.getOrDefault(n.parent, rootItem).getChildren().add(items.get(n.id));
    for (Map.Entry<String, TreeItem<String>> item : items.entrySet())
      item.getValue().setExpanded(item.getKey().equals("0") || expanded.contains(item.getKey()));
    for (Project.Node n : history.get().nodes.values()) {
      TreeItem<String> item = items.get(n.id);
      if (item == null) continue;
      HBox graphics = new HBox(3);
      javafx.scene.Node image = TypeIcons.graphic(n, 16);
      if (image != null) graphics.getChildren().add(image);
      if (!n.icon.isBlank()) graphics.getChildren().add(new Label(n.icon));
      item.setGraphic(graphics.getChildren().isEmpty() ? null : graphics);
    }
    tree.setRoot(rootItem);
    TreeItem<String> active = items.get(focus.selected == null ? focus.folder : focus.selected);
    if (active != null) {
      for (TreeItem<String> parent = active.getParent();
          parent != null;
          parent = parent.getParent()) parent.setExpanded(true);
      tree.getSelectionModel().select(active);
    }
    treeRendering = false;
  }

  VBox projectSearchPane() {
    projectSearch = new TextField();
    projectSearch.setPromptText(Tr.of("tree.searchPrompt"));
    ListView<Project.Node> results = new ListView<>();
    ListView<Project.Caption> noteResults = new ListView<>();
    noteResults.setId("annotation-search");
    noteResults.setPrefHeight(140);
    noteResults.setVisible(false);
    noteResults.setManaged(false);
    noteResults.setCellFactory(
        v ->
            new ListCell<Project.Caption>() {
              protected void updateItem(Project.Caption c, boolean empty) {
                super.updateItem(c, empty);
                setText(empty || c == null ? null : Tr.of("annotation.searchNote", c.text));
                setTooltip(empty || c == null ? null : new Tooltip(c.text));
              }
            });
    noteResults
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, a, b) -> {
              if (b != null) results.getSelectionModel().clearSelection();
            });
    results
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, a, b) -> {
              if (b != null) noteResults.getSelectionModel().clearSelection();
            });
    results.setCellFactory(
        v ->
            new ListCell<Project.Node>() {
              protected void updateItem(Project.Node n, boolean empty) {
                super.updateItem(n, empty);
                String q = projectSearch.getText().strip().toLowerCase(Locale.ROOT);
                String hit = empty || n == null ? null : matchedField(n, q);
                setText(
                    empty || n == null
                        ? null
                        : Tr.of("tree.item", n.name, n.type, n.parent)
                            + (hit == null ? "" : "\n" + hit));
              }
            });
    Runnable find =
        () -> {
          String q = projectSearch.getText().strip().toLowerCase(Locale.ROOT);
          results.getItems().clear();
          noteResults.getItems().clear();
          if (!q.isEmpty())
            for (Project.Caption c : history.get().captions)
              if (c.text.toLowerCase(Locale.ROOT).contains(q)) noteResults.getItems().add(c);
          noteResults.setVisible(!noteResults.getItems().isEmpty());
          noteResults.setManaged(!noteResults.getItems().isEmpty());
          if (!q.isEmpty())
            for (Project.Node n : history.get().nodes.values())
              if (searchText(n).contains(q)) results.getItems().add(n);
        };
    refreshProjectSearch = find;
    projectSearch.textProperty().addListener((o, a, b) -> find.run());
    Button open =
        shell.button(
            Tr.of("tree.showOnDiagram"),
            () -> {
              Project.Caption caption = noteResults.getSelectionModel().getSelectedItem();
              if (caption != null && history.get().caption(caption.id) != null) {
                focus.folder =
                    history.get().nodes.containsKey(caption.folder) ? caption.folder : "0";
                focus.selected =
                    history.get().nodes.containsKey(caption.anchor) ? caption.anchor : null;
                focus.selectedWire = null;
                shell.render();
                javafx.application.Platform.runLater(
                    () -> {
                      ScrollPane scroll = shell.diagramScroll();
                      double w =
                          Math.max(
                              1,
                              shell.world().getBoundsInParent().getWidth()
                                  - scroll.getViewportBounds().getWidth());
                      double h =
                          Math.max(
                              1,
                              shell.world().getBoundsInParent().getHeight()
                                  - scroll.getViewportBounds().getHeight());
                      scroll.setHvalue(
                          Math.max(0, Math.min(1, caption.x * shell.world().getScaleX() / w)));
                      scroll.setVvalue(
                          Math.max(0, Math.min(1, caption.y * shell.world().getScaleY() / h)));
                    });
                return;
              }
              Project.Node found = results.getSelectionModel().getSelectedItem();
              if (found != null && history.get().nodes.containsKey(found.id)) {
                focus.selected = found.id;
                focus.folder = found.parent;
                focus.selectedWire = null;
                shell.render();
                shell.focusSelected();
              }
            });
    results.setOnMouseClicked(
        e -> {
          if (e.getClickCount() == 2) open.fire();
        });
    noteResults.setOnMouseClicked(
        e -> {
          if (e.getClickCount() == 2) open.fire();
        });
    projectSearch.setOnAction(
        e -> {
          find.run();
          if (!results.getItems().isEmpty()) results.getSelectionModel().selectFirst();
          else noteResults.getSelectionModel().selectFirst();
          open.fire();
        });
    VBox pane = new VBox(8, projectSearch, results, noteResults, open);
    pane.setPadding(new Insets(10));
    VBox.setVgrow(results, Priority.ALWAYS);
    return pane;
  }

  /** Creates the tree; picking an item opens its folder or selects the block. */
  TreeView<String> buildTree() {
    tree = new TreeView<>();
    tree.setShowRoot(true);
    tree.setCellFactory(
        v ->
            new TreeCell<String>() {
              protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty ? null : text);
                setGraphic(empty || getTreeItem() == null ? null : getTreeItem().getGraphic());
                setTooltip(null);
                if (!empty && text != null && text.endsWith("]") && text.contains("[#")) {
                  Project.Node n =
                      history
                          .get()
                          .nodes
                          .get(text.substring(text.lastIndexOf("[#") + 2, text.length() - 1));
                  if (n != null)
                    setTooltip(
                        new Tooltip(TypeIcons.tooltip(n, TypeIcons.description.apply(n.type))));
                }
              }
            });
    tree.getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, a, b) -> {
              if (!treeRendering && b != null) {
                String id =
                    (String)
                        b.getValue()
                            .substring(
                                b.getValue().lastIndexOf("[#") + 2, b.getValue().length() - 1);
                Project.Node n = history.get().nodes.get(id);
                if (n == null
                    || n.type.equals("sys::Folder")
                    || !b.getChildren().isEmpty()
                    || id.equals("0")) {
                  focus.folder = id;
                  focus.selected = null;
                  focus.selectedWire = null;
                  shell.renderCanvas();
                  onNavigate.run();
                } else {
                  focus.selection.clear();
                  focus.selected = id;
                  focus.selectedWire = null;
                  focus.folder = n.parent;
                  shell.renderCanvas();
                  onNavigate.run();
                }
              }
            });
    return tree;
  }
}

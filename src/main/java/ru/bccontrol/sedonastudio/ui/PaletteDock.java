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

/** The palette tab: component types, search box and drag-and-drop onto the canvas. */
final class PaletteDock {
  private final History history;
  private final TypeCatalog typeCatalog;

  final PalettePane palette = new PalettePane();
  final TextField search = new TextField();

  PaletteDock(History history, TypeCatalog typeCatalog) {
    this.history = history;
    this.typeCatalog = typeCatalog;
  }

  /** Show the types matching the search box, for the kits of the current project. */
  void filter() {
    String query = search.getText().toLowerCase(Locale.ROOT);
    palette.update(typeCatalog.types, query, history.get().parts);
  }

  /** The palette tab: search box, type tree and the given command buttons. */
  VBox build(Runnable addBlock, Node... commands) {
    palette.setOnMouseClicked(
        e -> {
          if (e.getClickCount() == 2) addBlock.run();
        });
    palette.addEventFilter(
        javafx.scene.input.MouseEvent.DRAG_DETECTED,
        e -> {
          Project.Node template = palette.selected();
          if (template == null) return;
          Dragboard board = palette.startDragAndDrop(TransferMode.COPY);
          ClipboardContent content = new ClipboardContent();
          content.putString("sedona-type:" + template.kitPart + "|" + template.type);
          board.setContent(content);
          e.consume();
        });
    search.setPromptText(Tr.of("palette.searchPrompt"));
    search.textProperty().addListener((o, a, b) -> filter());
    VBox paletteBox = new VBox(7, search, palette);
    paletteBox.getChildren().addAll(commands);
    paletteBox.setPadding(new Insets(10));
    VBox.setVgrow(palette, Priority.ALWAYS);
    return paletteBox;
  }

  /** Lets the canvas accept a type dragged from the palette. */
  void acceptDropsOn(DiagramPane world, BiConsumer<Project.Node, Point2D> addAt) {
    world.setOnDragOver(
        e -> {
          if (e.getGestureSource() == palette && e.getDragboard().hasString())
            e.acceptTransferModes(TransferMode.COPY);
          e.consume();
        });
    world.setOnDragDropped(
        e -> {
          boolean ok = false;
          if (e.getGestureSource() == palette && e.getDragboard().hasString()) {
            Point2D location = world.sceneToLocal(e.getSceneX(), e.getSceneY());
            String type = e.getDragboard().getString().substring("sedona-type:".length());
            for (Project.Node template : typeCatalog.types)
              if ((template.kitPart + "|" + template.type).equals(type)) {
                addAt.accept(template, location);
                ok = true;
                break;
              }
          }
          e.setDropCompleted(ok);
          e.consume();
        });
  }
}

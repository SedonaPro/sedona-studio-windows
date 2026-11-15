package ru.bccontrol.sedonastudio.ui;

import java.util.Collection;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import javafx.stage.Stage;
import ru.bccontrol.sedonastudio.model.Project;

/**
 * What a feature may ask of the main window. Features take this instead of the whole {@link
 * StudioApp}, so their real dependencies are visible in the constructor.
 */
interface EditorShell {
  /** Owner window for dialogs. */
  Stage stage();

  DiagramPane world();

  Preferences prefs();

  /** Redraw tree, canvas and inspector from the current draft. */
  void render();

  /** Refresh the window title (file name, unsaved marker, connection). */
  void title();

  /** Apply one undoable change to the draft; refused while a device operation is running. */
  void edit(Consumer<Project> change);

  /** Step the draft back or forward in its history and redraw. */
  void undo();

  void redo();

  void selectGroup(Collection<String> ids);

  /** Scroll the canvas to the selected block. */
  void focusSelected();

  void fitDiagram();

  javafx.scene.Scene scene();

  javafx.scene.control.ScrollPane diagramScroll();

  javafx.scene.control.Slider zoomControl();

  /** Raw command-line arguments of this window's process. */
  java.util.List<String> launchArgs();

  void renderTree();

  void renderCanvas();

  /** The type colours changed: redraw the legend and the canvas. */
  void colorsChanged();

  /** A command button that is refused while a device operation is running. */
  javafx.scene.control.Button button(String text, Runnable action);

  /** Open a file or folder in the desktop's default application. */
  void showDocument(String uri);
}

package ru.bccontrol.sedonastudio.ui;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import javafx.collections.*;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.storage.*;

/** File library UI. Insert mutates only the local editor through its supplied command. */
public final class TemplateLibraryDialog {
  private TemplateLibraryDialog() {}

  public static void show(
      Stage owner,
      TemplateStore store,
      Supplier<Project> selection,
      Consumer<Project> insert,
      Consumer<Exception> errors,
      java.util.prefs.Preferences prefs,
      Consumer<Path> openFolder)
      throws Exception {
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle(Tr.of("templates.title"));
    dialog.getDialogPane().setId("template-library");
    dialog.setResizable(true);
    ObservableList<TemplateStore.Entry> entries = FXCollections.observableArrayList(store.list());
    FilteredList<TemplateStore.Entry> filtered = new FilteredList<>(entries);
    ListView<TemplateStore.Entry> list = new ListView<>(filtered);
    list.setPrefSize(740, 340);
    list.setId("template-list");
    list.setPlaceholder(new Label(Tr.of("templates.empty")));
    TextField search = new TextField();
    search.setPromptText(Tr.of("templates.searchPrompt"));
    search
        .textProperty()
        .addListener(
            (o, a, b) ->
                filtered.setPredicate(
                    e ->
                        e.name()
                            .toLowerCase(Locale.ROOT)
                            .contains(b.strip().toLowerCase(Locale.ROOT))));
    TextArea details = new TextArea();
    details.setEditable(false);
    details.setPrefRowCount(5);
    Label status = new Label(Tr.of("templates.insertHint"));
    status.setWrapText(true);
    Button save = new Button(Tr.of("templates.saveSelection")),
        add = new Button(Tr.of("templates.insert")),
        preview = new Button(Tr.of("templates.preview")),
        rename = new Button(Tr.of("templates.rename")),
        importFile = new Button(Tr.of("templates.import")),
        export = new Button(Tr.of("templates.export")),
        remove = new Button(Tr.of("sessions.remove")),
        folder = new Button(Tr.of("sessions.openFolder"));
    save.setId("template-save");
    add.setId("template-insert");
    preview.setId("template-preview-button");
    rename.setId("template-rename");
    remove.setId("template-remove");
    add.setDisable(true);
    preview.setDisable(true);
    rename.setDisable(true);
    export.setDisable(true);
    remove.setDisable(true);
    list.getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, a, b) -> {
              add.setDisable(b == null || !b.error().isEmpty());
              preview.setDisable(b == null || !b.error().isEmpty());
              rename.setDisable(b == null || !b.error().isEmpty());
              export.setDisable(b == null || !b.error().isEmpty());
              remove.setDisable(b == null);
              try {
                if (b == null) details.clear();
                else if (!b.error().isEmpty()) details.setText(b.error());
                else {
                  TemplateRequirements.Result required =
                      TemplateRequirements.resolve(store.load(b));
                  details.setText(
                      Tr.of(
                          "templates.details",
                          b,
                          required.message(),
                          String.join("\n", required.project().parts)));
                }
              } catch (Exception ex) {
                details.setText(ex.getMessage());
              }
            });
    Consumer<Runnable> run =
        action -> {
          try {
            action.run();
          } catch (Exception ex) {
            errors.accept(ex);
          }
        };
    Runnable refresh =
        () -> {
          try {
            entries.setAll(store.list());
          } catch (Exception ex) {
            throw new IllegalStateException(ex);
          }
        };
    preview.setOnAction(
        e ->
            run.accept(
                () -> {
                  var entry = list.getSelectionModel().getSelectedItem();
                  if (entry == null) return;
                  try {
                    Project fragment = store.load(entry);
                    Dialog<ButtonType> view = new Dialog<>();
                    view.initOwner(dialog.getDialogPane().getScene().getWindow());
                    view.setTitle(Tr.of("templates.previewTitle", fragment.name));
                    view.setResizable(true);
                    view.getDialogPane().setId("template-preview-dialog");
                    ReviewDiagramPane pane = ReviewDiagramPane.preview(fragment, prefs);
                    pane.setId("template-preview-pane");
                    pane.setStyle(owner.getScene().getRoot().getStyle());
                    view.getDialogPane().setContent(pane);
                    view.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
                    view.setOnShown(event -> pane.activate());
                    view.showAndWait();
                  } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                  }
                }));
    list.setOnMouseClicked(
        e -> {
          if (e.getClickCount() == 2) preview.fire();
        });
    rename.setOnAction(
        e ->
            run.accept(
                () -> {
                  var entry = list.getSelectionModel().getSelectedItem();
                  if (entry == null) return;
                  TextInputDialog name = new TextInputDialog(entry.name());
                  name.initOwner(dialog.getDialogPane().getScene().getWindow());
                  name.setTitle(Tr.of("templates.renameTitle"));
                  name.setHeaderText(Tr.of("templates.renameHint"));
                  name.getDialogPane().setId("template-rename-dialog");
                  name.showAndWait()
                      .ifPresent(
                          text -> {
                            try {
                              store.rename(entry, text);
                              refresh.run();
                              entries.stream()
                                  .filter(item -> item.path().equals(entry.path()))
                                  .findFirst()
                                  .ifPresent(list.getSelectionModel()::select);
                              status.setText(Tr.of("templates.renamed"));
                            } catch (Exception ex) {
                              throw new IllegalStateException(ex);
                            }
                          });
                }));
    save.setOnAction(
        e ->
            run.accept(
                () -> {
                  TextInputDialog name = new TextInputDialog(Tr.of("templates.defaultName"));
                  name.initOwner(owner);
                  name.getDialogPane().setId("template-name");
                  name.setTitle(Tr.of("templates.saveTitle"));
                  name.setHeaderText(Tr.of("templates.saveHint"));
                  name.showAndWait()
                      .ifPresent(
                          text -> {
                            try {
                              Project p = selection.get();
                              store.add(TemplateStore.prepare(p, p.nodes.keySet(), text));
                              refresh.run();
                              status.setText(Tr.of("templates.saved", store.root()));
                            } catch (Exception ex) {
                              throw new IllegalStateException(ex);
                            }
                          });
                }));
    add.setOnAction(
        e ->
            run.accept(
                () -> {
                  try {
                    var entry = list.getSelectionModel().getSelectedItem();
                    if (entry != null) {
                      insert.accept(store.load(entry));
                      dialog.close();
                    }
                  } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                  }
                }));
    importFile.setOnAction(
        e ->
            run.accept(
                () -> {
                  FileChooser chooser = chooser();
                  File file = chooser.showOpenDialog(owner);
                  if (file == null) return;
                  try {
                    store.add(ProjectStore.load(file.toPath()));
                    refresh.run();
                    status.setText(Tr.of("templates.imported"));
                  } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                  }
                }));
    export.setOnAction(
        e ->
            run.accept(
                () -> {
                  var entry = list.getSelectionModel().getSelectedItem();
                  if (entry == null) return;
                  FileChooser chooser = chooser();
                  chooser.setInitialFileName("template.sedona.json");
                  File file = chooser.showSaveDialog(owner);
                  if (file == null) return;
                  try {
                    ProjectStore.save(store.load(entry), file.toPath());
                    status.setText(Tr.of("templates.exported", file));
                  } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                  }
                }));
    remove.setOnAction(
        e ->
            run.accept(
                () -> {
                  var entry = list.getSelectionModel().getSelectedItem();
                  if (entry == null) return;
                  try {
                    Path archived = store.remove(entry);
                    refresh.run();
                    status.setText(Tr.of("templates.archived", archived));
                  } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                  }
                }));
    folder.setOnAction(
        e ->
            run.accept(
                () -> {
                  try {
                    Files.createDirectories(store.root());
                    openFolder.accept(store.root());
                  } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                  }
                }));
    VBox content =
        new VBox(
            10,
            search,
            list,
            new FlowPane(8, 8, save, preview, add, rename, importFile, export, remove, folder),
            details,
            status,
            new Label(Tr.of("templates.libraryFolder", store.root())));
    content.setPadding(new Insets(12));
    VBox.setVgrow(list, Priority.ALWAYS);
    dialog.getDialogPane().setContent(content);
    dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    dialog.showAndWait();
  }

  private static FileChooser chooser() {
    FileChooser c = new FileChooser();
    c.getExtensionFilters()
        .add(new FileChooser.ExtensionFilter(Tr.of("templates.fileType"), "*.sedona.json"));
    return c;
  }
}

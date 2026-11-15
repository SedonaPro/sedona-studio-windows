package ru.bccontrol.sedonastudio.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.storage.ManifestArchive;
import ru.bccontrol.sedonastudio.toolchain.KitWorkshop;
import ru.bccontrol.sedonastudio.toolchain.Sdk;
import ru.bccontrol.sedonastudio.toolchain.Toolchain;

/**
 * The kit workshop window: start a kit from a template, compile it, run its unit tests and put it
 * into the palette. Sources are edited in an external editor; a message opens its file there. Needs
 * an external SDK. Compiling runs on its own thread, never on the device thread.
 */
final class KitWorkshopWindow {
  private final DeviceLink deviceLink;
  private final Notices notices;
  private final ProjectFiles projectFiles;
  private final EditorShell shell;
  /** Switches the project to a kit build; set by StudioApp (EditActions.useKitBuild). */
  java.util.function.ToIntFunction<String> useInProject = part -> 0;

  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "sedona-kit-workshop");
            t.setDaemon(true);
            return t;
          });
  private Stage window;
  final ComboBox<Path> kits = new ComboBox<>();
  final ListView<KitWorkshop.Diagnostic> messages = new ListView<>();
  final TextArea log = new TextArea();
  final Label status = new Label();
  private final TextField editorCommand = new TextField();
  /** The kit's source files and the built-in editor for the one that is open. */
  final ListView<Path> files = new ListView<>();

  final TextArea editor = new TextArea();
  private final Label fileName = new Label();
  private Path openFile;
  private boolean unsaved, loading;
  private final List<Button> commands = new ArrayList<>();
  /** True while the compiler or the tests are running. */
  boolean running;
  /** What the last build or test run produced. */
  KitWorkshop.Result last;

  KitWorkshopWindow(
      DeviceLink deviceLink, Notices notices, ProjectFiles projectFiles, EditorShell shell) {
    this.deviceLink = deviceLink;
    this.notices = notices;
    this.projectFiles = projectFiles;
    this.shell = shell;
  }

  /** The window's scene once it has been shown; for the built-in checks. */
  Scene scene() {
    return window == null ? null : window.getScene();
  }

  private Optional<Sdk> sdk() {
    return Sdk.find(shell.prefs().get("sdkPath", null));
  }

  private Path workFolder() {
    return Paths.get(System.getProperty("studio.root"), "kit-workshop");
  }

  void show() {
    if (window == null) build();
    Optional<Sdk> sdk = sdk();
    status.setText(sdk.isPresent() ? Tr.of("kit.sdk", sdk.get()) : Tr.of("sim.noSdk"));
    window.show();
    window.toFront();
  }

  private void build() {
    window = new Stage();
    window.initOwner(shell.stage());
    window.setTitle(Tr.of("kit.title"));
    for (String folder : shell.prefs().get("kitFolders", "").split("\n"))
      if (!folder.isBlank() && Files.isRegularFile(Paths.get(folder, "kit.xml")))
        kits.getItems().add(Paths.get(folder));
    if (!kits.getItems().isEmpty()) kits.setValue(kits.getItems().get(0));
    kits.setMaxWidth(Double.MAX_VALUE);
    kits.setPromptText(Tr.of("kit.choose"));
    HBox.setHgrow(kits, Priority.ALWAYS);
    Button create = new Button(Tr.of("kit.new")), add = new Button(Tr.of("kit.addFolder"));
    create.setOnAction(e -> notices.guard(this::askNewKit));
    add.setOnAction(e -> notices.guard(this::askFolder));
    Button compile = command(Tr.of("kit.build"), this::build0),
        test = command(Tr.of("kit.test"), this::test),
        palette = command(Tr.of("kit.toPalette"), this::toPalette),
        update = command(Tr.of("kit.updateProject"), this::updateProject),
        all = command(Tr.of("kit.sdkToPalette"), this::sdkToPalette),
        folder = new Button(Tr.of("kit.openFolder"));
    folder.setOnAction(
        e -> {
          if (kits.getValue() != null) shell.showDocument(kits.getValue().toUri().toString());
        });
    editorCommand.setText(shell.prefs().get("editorCommand", ""));
    editorCommand.setPromptText(Tr.of("kit.editorPrompt"));
    editorCommand
        .textProperty()
        .addListener((o, a, b) -> shell.prefs().put("editorCommand", b.strip()));
    HBox.setHgrow(editorCommand, Priority.ALWAYS);
    messages.setPlaceholder(new Label(Tr.of("kit.noMessages")));
    messages.setOnMouseClicked(
        e -> {
          KitWorkshop.Diagnostic chosen = messages.getSelectionModel().getSelectedItem();
          if (e.getClickCount() == 2 && chosen != null) notices.guard(() -> open(chosen));
        });
    files.setPrefWidth(190);
    files.setCellFactory(
        list ->
            new javafx.scene.control.ListCell<>() {
              @Override
              protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                setText(
                    empty || item == null || kits.getValue() == null
                        ? null
                        : kits.getValue().relativize(item).toString());
              }
            });
    files
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, a, b) -> {
              if (b != null && !b.equals(openFile)) notices.guard(() -> openFile(b, 0));
            });
    kits.valueProperty().addListener((o, a, b) -> listFiles());
    editor.setStyle("-fx-font-family: monospace;");
    editor.setPromptText(Tr.of("kit.editorEmpty"));
    editor
        .textProperty()
        .addListener(
            (o, a, b) -> {
              if (!loading && openFile != null) {
                unsaved = true;
                fileName.setText(kits.getValue().relativize(openFile) + " *");
              }
            });
    editor.addEventFilter(
        javafx.scene.input.KeyEvent.KEY_PRESSED,
        e -> {
          if (e.isControlDown() && e.getCode() == javafx.scene.input.KeyCode.S) {
            notices.guard(this::save);
            e.consume();
          }
        });
    Button saveFile = new Button(Tr.of("kit.saveFile"));
    saveFile.setOnAction(e -> notices.guard(this::save));
    VBox editing = new VBox(6, new HBox(8, fileName, saveFile), editor);
    VBox.setVgrow(editor, Priority.ALWAYS);
    SplitPane sources = new SplitPane(files, editing);
    sources.setDividerPositions(.22);
    listFiles();
    log.setEditable(false);
    log.setStyle("-fx-font-family: monospace;");
    SplitPane output = new SplitPane(sources, messages, log);
    output.setOrientation(javafx.geometry.Orientation.VERTICAL);
    output.setDividerPositions(.55, .72);
    VBox.setVgrow(output, Priority.ALWAYS);
    status.setWrapText(true);
    VBox content =
        new VBox(
            10,
            new HBox(8, kits, create, add),
            new HBox(8, compile, test, palette, update, all, folder),
            new HBox(8, new Label(Tr.of("kit.editor")), editorCommand),
            output,
            status);
    content.setPadding(new Insets(12));
    Scene scene = new Scene(content, 980, 760);
    scene.getStylesheets().addAll(shell.scene().getStylesheets());
    window.setScene(scene);
  }

  /** The kit's kit.xml and Sedona sources, a few folders deep. */
  private void listFiles() {
    files.getItems().clear();
    Path kit = kits.getValue();
    if (kit == null) return;
    try (var found = Files.walk(kit, 4)) {
      found
          .filter(Files::isRegularFile)
          .filter(
              f -> {
                String name = f.getFileName().toString();
                return name.endsWith(".sedona") || name.equals("kit.xml") || name.endsWith(".c");
              })
          .sorted()
          .forEach(files.getItems()::add);
    } catch (java.io.IOException e) {
      // An unreadable folder simply shows no files.
    }
    if (openFile != null && !openFile.startsWith(kit)) {
      openFile = null;
      loading = true;
      editor.clear();
      loading = false;
      fileName.setText("");
    }
  }

  /** Shows a source file in the built-in editor, with the given line (from 1) selected. */
  void openFile(Path file, int line) {
    if (unsaved && openFile != null && !file.equals(openFile)) save();
    try {
      if (!file.equals(openFile) || !unsaved) {
        if (Files.size(file) > 2 * 1024 * 1024)
          throw new IllegalArgumentException(Tr.of("kit.fileTooLarge", file.getFileName()));
        loading = true;
        editor.setText(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
        loading = false;
        openFile = file;
        unsaved = false;
        fileName.setText(kits.getValue().relativize(file).toString());
      }
    } catch (java.io.IOException e) {
      loading = false;
      throw new IllegalStateException(e.getMessage());
    }
    files.getSelectionModel().select(file);
    if (line > 0) {
      String text = editor.getText();
      int start = 0;
      for (int i = 1; i < line && start >= 0; i++) start = text.indexOf('\n', start) + 1;
      if (start < 0 || (line > 1 && start == 0)) return;
      int end = text.indexOf('\n', start);
      editor.selectRange(end < 0 ? text.length() : end, start);
      editor.requestFocus();
    }
  }

  /** Writes the open file back; Build and Test do this first. */
  void save() {
    if (openFile == null || !unsaved) return;
    try {
      Files.writeString(openFile, editor.getText(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e.getMessage());
    }
    unsaved = false;
    fileName.setText(kits.getValue().relativize(openFile).toString());
  }

  private Button command(String text, Runnable action) {
    Button button = new Button(text);
    button.setOnAction(e -> notices.guard(action));
    commands.add(button);
    return button;
  }

  private void remember() {
    // The built-in checks must not leave their folders in the user's settings.
    if (shell.launchArgs().stream().anyMatch(a -> a.startsWith("--kit-smoke"))) return;
    List<String> folders = new ArrayList<>();
    for (Path kit : kits.getItems()) folders.add(kit.toString());
    shell.prefs().put("kitFolders", String.join("\n", folders));
  }

  /** Adds the kit folder to the list and selects it. */
  void select(Path kitFolder) {
    if (window == null) build();
    if (!kits.getItems().contains(kitFolder)) kits.getItems().add(0, kitFolder);
    kits.setValue(kitFolder);
    remember();
  }

  private void askNewKit() {
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.initOwner(window);
    dialog.setTitle(Tr.of("kit.new"));
    TextField vendor = new TextField(shell.prefs().get("kitVendor", "Acme")),
        name = new TextField(),
        parent = new TextField(Paths.get(System.getProperty("studio.root"), "kits").toString());
    name.setPromptText("acmeBoiler");
    parent.setPrefColumnCount(36);
    GridPane grid = new GridPane();
    grid.setHgap(10);
    grid.setVgap(8);
    grid.addRow(0, new Label(Tr.of("kit.vendor")), vendor);
    grid.addRow(1, new Label(Tr.of("kit.name")), name);
    grid.addRow(2, new Label(Tr.of("kit.parentFolder")), parent);
    // With C natives the kit is created inside the SDK tree and Build rebuilds the VM.
    javafx.scene.control.CheckBox natives =
        new javafx.scene.control.CheckBox(Tr.of("kit.withNatives"));
    TextField domain = new TextField("logic");
    boolean tree =
        sdk()
            .map(
                found ->
                    found.tree != null
                        || ru.bccontrol.sedonastudio.toolchain.WindowsNative.available(found))
            .orElse(false);
    natives.setDisable(!tree);
    domain.disableProperty().bind(natives.selectedProperty().not());
    parent.disableProperty().bind(natives.selectedProperty());
    Label nativesHint =
        new Label(
            sdk().map(ru.bccontrol.sedonastudio.toolchain.WindowsNative::available).orElse(false)
                ? Tr.of("kit.windowsNativesHint")
                : tree ? Tr.of("kit.nativesHint") : Tr.of("kit.nativesNoTree"));
    nativesHint.setWrapText(true);
    nativesHint.setMaxWidth(520);
    grid.add(new Label(Tr.of("kit.newHint")), 0, 3, 2, 1);
    grid.add(natives, 0, 4, 2, 1);
    grid.addRow(5, new Label(Tr.of("kit.domain")), domain);
    grid.add(nativesHint, 0, 6, 2, 1);
    dialog.getDialogPane().setContent(grid);
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
    try {
      if (natives.isSelected())
        createNative(domain.getText().strip(), vendor.getText().strip(), name.getText().strip());
      else
        create(
            Paths.get(parent.getText().strip()), vendor.getText().strip(), name.getText().strip());
      shell.prefs().put("kitVendor", vendor.getText().strip());
    } catch (Exception e) {
      notices.error(e);
    }
  }

  /** Creates a kit with a C native method inside the SDK tree and selects it. */
  void createNative(String domain, String vendor, String name) throws Exception {
    Sdk sdk = sdk().orElseThrow(() -> new IllegalStateException(Tr.of("sim.noSdk")));
    Path folder =
        new KitWorkshop(new Toolchain(sdk)).createNative(domain, vendor, name, workFolder());
    select(folder);
    listFiles();
    status.setText(Tr.of("kit.createdNative", folder));
  }

  /** Creates a kit from the template and selects it. */
  void create(Path parent, String vendor, String name) throws Exception {
    Path folder = KitWorkshop.create(parent, vendor, name);
    select(folder);
    listFiles();
    status.setText(Tr.of("kit.created", folder));
  }

  private void askFolder() {
    DirectoryChooser chooser = new DirectoryChooser();
    chooser.setTitle(Tr.of("kit.addFolder"));
    File chosen = chooser.showDialog(window);
    if (chosen == null) return;
    if (!Files.isRegularFile(chosen.toPath().resolve("kit.xml")))
      throw new IllegalArgumentException(Tr.of("kit.noKitXml", chosen));
    select(chosen.toPath());
  }

  void build0() {
    run(Tr.of("kit.building"), false);
  }

  void test() {
    run(Tr.of("kit.testing"), true);
  }

  private void run(String what, boolean withTests) {
    Path kit = kits.getValue();
    if (kit == null) throw new IllegalStateException(Tr.of("kit.choose"));
    if (running) throw new IllegalStateException(Tr.of("notice.busy"));
    Sdk sdk = sdk().orElseThrow(() -> new IllegalStateException(Tr.of("sim.noSdk")));
    save();
    KitWorkshop workshop = new KitWorkshop(new Toolchain(sdk));
    busy(true);
    status.setText(what);
    messages.getItems().clear();
    log.clear();
    Path work = workFolder();
    worker.submit(
        () -> {
          KitWorkshop.Result result = null;
          Exception failure = null;
          try {
            result = withTests ? workshop.test(kit, work) : workshop.build(kit, work);
          } catch (Exception e) {
            failure = e;
          }
          KitWorkshop.Result done = result;
          Exception error = failure;
          Platform.runLater(
              () -> {
                busy(false);
                last = done;
                if (error != null) {
                  status.setText(Tr.of("notice.error", error.getMessage()));
                  return;
                }
                messages.getItems().setAll(done.diagnostics());
                log.setText(done.log());
                log.positionCaret(log.getLength());
                status.setText(
                    !done.ok()
                        ? (done.failures() > 0
                            ? Tr.of("kit.testsFailed", done.failures(), done.asserts())
                            : Tr.of("kit.failed", done.diagnostics().size()))
                        : withTests
                            ? Tr.of("kit.testsPassed", done.part(), done.asserts())
                            : Tr.of("kit.built", done.part()));
              });
        });
  }

  private void busy(boolean now) {
    running = now;
    for (Button button : commands) button.setDisable(now);
  }

  /** Makes the last compiled build of the kit, and what it depends on, available in the palette. */
  void toPalette() {
    if (last == null || last.part() == null)
      throw new IllegalStateException(Tr.of("kit.buildFirst"));
    Sdk sdk = sdk().orElseThrow(() -> new IllegalStateException(Tr.of("sim.noSdk")));
    importToPalette(new Toolchain(sdk).withDependencies(List.of(last.part())), sdk);
  }

  /** After a rebuild: the blocks of this kit in the open project take the new build. */
  void updateProject() {
    if (last == null || last.part() == null)
      throw new IllegalStateException(Tr.of("kit.buildFirst"));
    status.setText(Tr.of("kit.projectUpdated", last.part(), useInProject.applyAsInt(last.part())));
  }

  /** Makes every kit of the SDK available in the palette (the newest build of each). */
  void sdkToPalette() {
    Sdk sdk = sdk().orElseThrow(() -> new IllegalStateException(Tr.of("sim.noSdk")));
    List<String> parts = new ArrayList<>();
    try (var folders = Files.newDirectoryStream(sdk.home.resolve("kits"))) {
      for (Path folder : folders) {
        String part = sdk.kitPart(folder.getFileName().toString());
        if (part != null) parts.add(part);
      }
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e.getMessage());
    }
    importToPalette(parts, sdk);
  }

  private void importToPalette(List<String> parts, Sdk sdk) {
    if (deviceLink.session.connected())
      throw new IllegalStateException(Tr.of("files.paletteLocked"));
    // The manifest store is shared with the device thread, so the import runs there.
    deviceLink.work(
        Tr.of("kit.importing", parts.size()),
        () -> {
          int imported = 0;
          for (String part : parts) {
            byte[] xml = sdk.kitSource().manifest(part);
            if (xml == null) continue;
            ManifestArchive.importManifests(xml);
            imported++;
          }
          return imported;
        },
        imported -> {
          status.setText(Tr.of("kit.inPalette", imported));
          projectFiles.loadLocalPalette();
        });
  }

  /** Opens the file of a message: in the built-in editor, or with the user's editor command. */
  void open(KitWorkshop.Diagnostic place) {
    if (place.file() == null) return;
    String command = editorCommand.getText().strip();
    if (command.isEmpty()) {
      openFile(place.file(), place.line());
      return;
    }
    List<String> arguments = new ArrayList<>();
    for (String word : command.split("\\s+"))
      arguments.add(
          word.replace("{file}", place.file().toString())
              .replace("{line}", Integer.toString(Math.max(1, place.line())))
              .replace("{column}", Integer.toString(Math.max(1, place.column()))));
    if (!command.contains("{file}")) arguments.add(place.file().toString());
    try {
      new ProcessBuilder(arguments).inheritIO().start();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e.getMessage());
    }
  }
}

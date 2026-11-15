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

/** Desktop composition root. All device work runs outside the JavaFX thread. */
public final class StudioApp extends Application implements EditorShell {
  final History history = new History();
  Stage stage;
  Scene scene;
  BorderPane root;
  DiagramPane world;
  private TextArea checks;

  public Stage stage() {
    return stage;
  }

  public DiagramPane world() {
    return world;
  }

  public java.util.prefs.Preferences prefs() {
    return prefs;
  }

  public Scene scene() {
    return scene;
  }

  public ScrollPane diagramScroll() {
    return diagramView.diagramScroll;
  }

  public Slider zoomControl() {
    return diagramView.zoomControl;
  }

  public List<String> launchArgs() {
    return getParameters().getRaw();
  }

  public void showDocument(String uri) {
    getHostServices().showDocument(uri);
  }

  public void selectGroup(Collection<String> ids) {
    focus.selection.clear();
    focus.selection.addAll(ids);
    focus.selected = focus.selection.stream().findFirst().orElse(null);
    focus.selectedWire = null;
    projectTree.renderTree();
    inspectorPane.renderInspector();
  }

  java.util.prefs.Preferences prefs =
      java.util.prefs.Preferences.userRoot().node("ru/bccontrol/sedona-studio-windows");
  // Shared building blocks first, then the features that use them.
  final EditorFocus focus = new EditorFocus(history);
  final ViewOptions view = new ViewOptions();
  final TypeCatalog typeCatalog = new TypeCatalog();
  final PaletteDock paletteDock = new PaletteDock(history, typeCatalog);
  final Notices notices = new Notices(() -> stage);
  final DeviceLink deviceLink = new DeviceLink(notices, () -> this.workspace.resetSessionTab());
  final SelfTest selfTest = new SelfTest(this);
  final SavedView savedView = new SavedView(history, focus, view, notices, this);
  final LiveValues liveValues = new LiveValues(focus, deviceLink, notices, this);
  final PanelFeature panel = new PanelFeature(history, deviceLink, liveValues);
  final SessionWorkspace workspace =
      new SessionWorkspace(history, focus, savedView, deviceLink, notices, liveValues, panel, this);
  final ProjectFiles projectFiles =
      new ProjectFiles(
          history, focus, deviceLink, notices, typeCatalog, paletteDock, workspace, this);
  final EditActions editing =
      new EditActions(history, focus, deviceLink, notices, typeCatalog, paletteDock, this);
  final InspectorPane inspectorPane =
      new InspectorPane(history, focus, deviceLink, liveValues, editing, this);
  final SettingsDialogs dialogs = new SettingsDialogs(view, liveValues, this);
  final DeviceConnection deviceConnection =
      new DeviceConnection(
          history,
          focus,
          deviceLink,
          notices,
          typeCatalog,
          paletteDock,
          liveValues,
          panel,
          savedView,
          workspace,
          projectFiles,
          this);
  final DeviceChanges deviceChanges =
      new DeviceChanges(history, deviceLink, notices, liveValues, this, deviceConnection);
  final DraftEdits draftEdits =
      new DraftEdits(
          history,
          deviceLink.busy::get,
          () -> {
            projectFiles.dirty = true;
            render();
            deviceChanges.draftChanged(view.autoApply);
          },
          notices::message);
  final ManifestsDialog manifestsDialog =
      new ManifestsDialog(history, deviceLink, notices, projectFiles, this);
  final ProjectTree projectTree = new ProjectTree(history, focus, this);
  final DiagramView diagramView = new DiagramView(history, focus, view, notices, this);
  final DeviceUpload deviceUpload =
      new DeviceUpload(history, deviceLink, notices, deviceConnection, this);
  final KitWorkshopWindow kitWorkshop =
      new KitWorkshopWindow(deviceLink, notices, projectFiles, this);
  final Simulator simulator =
      new Simulator(history, deviceLink, notices, projectFiles, deviceConnection, this);
  final SessionsDialog sessionsDialog = new SessionsDialog(notices, workspace, this);
  final Shortcuts shortcuts =
      new Shortcuts(
          history, focus, deviceLink, notices, projectFiles, editing, dialogs, diagramView, this);
  final EditorMenus menus =
      new EditorMenus(
          deviceLink,
          notices,
          view,
          projectFiles,
          editing,
          liveValues,
          dialogs,
          deviceConnection,
          deviceChanges,
          manifestsDialog,
          diagramView,
          kitWorkshop,
          deviceUpload,
          this);

  public Button button(String text, Runnable action) {
    Button b = new Button(text);
    if (text.equals(Tr.of("toolbar.pinLayout")))
      b.setTooltip(new Tooltip(Tr.of("toolbar.pinLayoutTip")));
    if (text.equals(Tr.of("toolbar.simulator")))
      b.setTooltip(new Tooltip(Tr.of("toolbar.simulatorTip")));
    if (text.equals("↶")) b.setTooltip(new Tooltip(Tr.of("toolbar.undoTip")));
    if (text.equals("↷")) b.setTooltip(new Tooltip(Tr.of("toolbar.redoTip")));
    if (text.equals("?")) b.setTooltip(new Tooltip(Tr.of("toolbar.helpTip")));
    b.setOnAction(
        e -> {
          if (deviceLink.busy.get()) {
            notices.message(Tr.of("notice.busy"));
            return;
          }
          notices.guard(action);
        });
    return b;
  }

  /** Connect the features to each other and to the window; they do not reach back into it. */
  private void wireFeatures() {
    deviceLink.onSettled = draftEdits::settled;
    TypeIcons.description = this::typeHelp;
    liveValues.onHousekeeping =
        () -> {
          workspace.serviceSessionTabs();
          selfTest.checkWorkspaceTest();
        };
    liveValues.onValuesChanged =
        () -> {
          world.refreshValues();
          inspectorPane.refreshWireSummary();
          panel.publish();
        };
    liveValues.extraWatch = panel::addWatchedTo;
    liveValues.onSample = selfTest::observeSample;
    deviceConnection.afterConnect = selfTest::afterConnect;
    diagramView.onRendered = savedView::queueSave;
    projectTree.onNavigate = inspectorPane::renderInspector;
    simulator.onChange = this::title;
    kitWorkshop.useInProject = editing::useKitBuild;
    deviceUpload.knownPassword = simulator::password;
    workspace.onConfirmedConnect = deviceConnection::continueConfirmedConnection;
  }

  public void start(Stage stage) throws Exception {
    this.stage = stage;
    wireFeatures();
    stage.getIcons().add(new javafx.scene.image.Image(getClass().getResourceAsStream("/icon.png")));
    typeCatalog.types.addAll(Catalog.bundled());
    root = new BorderPane();
    root.getStyleClass().add("studio");
    scene = new Scene(root, 1440, 900);
    scene.getStylesheets().add(getClass().getResource("/studio.css").toExternalForm());
    ToolBar top = buildToolbar();
    loadViewPreferences();
    HBox nav = buildNavigationBar();
    workspace.configureTabs();
    root.setTop(new VBox(workspace.deviceTabs, top, nav));
    TabPane left = buildLeftPanel();
    buildDiagram();
    ScrollPane scroll = diagramView.buildScroll();
    inspectorPane.content = new VBox(10);
    inspectorPane.content.setPadding(new Insets(15));
    ScrollPane properties = new ScrollPane(inspectorPane.content);
    properties.setFitToWidth(true);
    properties.setMinWidth(240);
    SplitPane horizontal = new SplitPane(left, scroll, panel.buildRightTabs(properties));
    savedView.horizontalPanels = horizontal;
    savedView.leftTabs = left;
    horizontal.setDividerPositions(
        prefs.getDouble("paletteWidth", .17), prefs.getDouble("inspectorWidth", .78));
    TabPane bottom = buildBottomPanel();
    SplitPane vertical = new SplitPane(horizontal, bottom);
    savedView.verticalPanels = vertical;
    savedView.bottomTabs = bottom;
    vertical.setOrientation(Orientation.VERTICAL);
    vertical.setDividerPositions(prefs.getDouble("trendHeight", .76));
    root.setCenter(vertical);
    notices.status = new Label(Tr.of("app.statusOffline"));
    notices.status.setMaxWidth(Double.MAX_VALUE);
    notices.status.getStyleClass().add("status");
    root.setBottom(notices.status);
    shortcuts.install();
    configureStage();
    dialogs.theme = prefs.get("theme", "Arctic");
    dialogs.applyTheme();
    projectFiles.demo();
    TypeIcons.configure(prefs.get("iconsPath", ""), prefs.getBoolean("typeIcons", true), prefs.getBoolean("canvasNotes", true), this::render);
    runLaunchSequence();
  }

  /** Main toolbar: file, undo/redo, device and session commands. */
  private ToolBar buildToolbar() {
    ToolBar top =
        new ToolBar(
            button(Tr.of("toolbar.open"), projectFiles::open),
            button(Tr.of("toolbar.save"), projectFiles::save),
            new Separator(),
            button("↶", this::undo),
            button("↷", this::redo),
            new Separator(),
            button(Tr.of("toolbar.connect"), deviceConnection::connect),
            button(Tr.of("toolbar.read"), deviceConnection::readDevice),
            button(Tr.of("toolbar.apply"), deviceChanges::apply),
            button(Tr.of("toolbar.simulator"), simulator::toggle),
            new Separator(),
            button(Tr.of("toolbar.sessions"), sessionsDialog::show),
            menus.editorMenu(),
            menus.optionsMenu());
    return top;
  }

  /** Editor options remembered between runs. */
  private void loadViewPreferences() {
    view.avoidOverlap = prefs.getBoolean("avoidOverlap", true);
    view.autoApply = prefs.getBoolean("autoApply", false);
    for (int i = 0; i < DiagramPane.KINDS.length; i++)
      try {
        DiagramPane.KIND_COLORS.put(
            DiagramPane.KINDS[i],
            javafx.scene.paint.Color.web(
                prefs.get("color." + DiagramPane.KINDS[i], DiagramPane.DEFAULT_COLORS[i])));
      } catch (IllegalArgumentException damaged) {
        // A damaged setting keeps the default colour.
      }
    view.snapGrid = prefs.getBoolean("snapGrid", true);
    view.showGrid = prefs.getBoolean("showGrid", true);
    view.gridStep = Math.max(12, Math.min(96, prefs.getInt("gridStep", 24)));
    view.gridWidth = Math.max(0.5, Math.min(3, prefs.getDouble("gridWidth", 1)));
    view.gridContrast = Math.max(0.03, Math.min(0.4, prefs.getDouble("gridContrast", 0.12)));
    liveValues.restoreInterval(
        PollingInterval.restore(
            prefs.getInt("liveIntervalMs", -1), prefs.getInt("liveInterval", 1)));
    view.showValues = prefs.getBoolean("showValues", true);
  }

  /** Second row: folder path, zoom tools and the online-values controls. */
  private HBox buildNavigationBar() {
    Slider zoomControl = diagramView.buildZoomControl();
    Label liveState = liveValues.buildControls(dialogs::settings);
    HBox zoomTools =
        new HBox(
            8,
            new Label(Tr.of("toolbar.zoom")),
            zoomControl,
            button("100%", () -> zoomControl.setValue(1)));
    zoomTools.setId("zoom-tools");
    zoomTools.setVisible(prefs.getBoolean("showZoomTools", false));
    zoomTools.managedProperty().bind(zoomTools.visibleProperty());
    HBox nav =
        new HBox(
            14,
            button(Tr.of("toolbar.upFolder"), diagramView::upFolder),
            diagramView.breadcrumb,
            zoomTools,
            button(Tr.of("toolbar.fit"), diagramView::fitDiagram),
            liveState,
            diagramView.legend());
    nav.setPadding(new Insets(8, 14, 8, 14));
    return nav;
  }

  /** Left side: palette, project tree and search. */
  private TabPane buildLeftPanel() {
    TabPane left =
        new TabPane(
            new Tab(
                Tr.of("tab.palette"),
                paletteDock.build(
                    () -> notices.guard(editing::addBlock),
                    button(Tr.of("palette.addBlock"), editing::addBlock),
                    button(Tr.of("palette.localManifests"), projectFiles::loadLocalPalette))),
            new Tab(Tr.of("tab.tree"), projectTree.buildTree()),
            new Tab(Tr.of("tab.search"), projectTree.projectSearchPane()));
    left.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    left.setMinWidth(180);
    return left;
  }

  /** The diagram canvas and its callbacks into the editor. */
  private void buildDiagram() {
    DiagramHost host =
        new DiagramHost(history, focus, view, notices, liveValues, inspectorPane, editing, this);
    host.typeHelp = this::typeHelp;
    world = new DiagramPane(host);
    paletteDock.acceptDropsOn(
        world,
        (template, at) -> notices.guard(() -> editing.addBlockAt(template, at.getX(), at.getY())));
  }

  /** Bottom side: trends, log and checks. */
  private TabPane buildBottomPanel() {
    notices.log = new TextArea();
    notices.log.setEditable(false);
    checks = new TextArea();
    checks.setEditable(false);
    TabPane bottom =
        new TabPane(
            new Tab(Tr.of("tab.trends"), liveValues.buildTrendChart()),
            new Tab(Tr.of("tab.log"), notices.log),
            new Tab(Tr.of("tab.checks"), checks));
    bottom.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    return bottom;
  }

  /** Window size limits and the close/exit sequence. */
  private void configureStage() {
    stage.setScene(scene);
    stage.setMinWidth(1100);
    stage.setMinHeight(700);
    stage.setOnCloseRequest(
        e -> {
          if (deviceLink.busy.get()) {
            notices.message(Tr.of("app.waitBeforeExit"));
            e.consume();
            return;
          }
          if (!projectFiles.discard()) {
            e.consume();
            return;
          }
          try {
            workspace.saveBeforeSessionSwitch();
            SessionStore.closeHiddenPeers();
          } catch (Exception ex) {
            notices.error(ex);
            e.consume();
            return;
          }
          workspace.shutdownSession();
        });
  }

  /** What happens after the window is built: restore the workspace and act on launch flags. */
  private void runLaunchSequence() throws Exception {
    if (!getParameters().getRaw().contains("--smoke")
        || getParameters().getRaw().contains("--restore-smoke")) {
      if (!SessionStore.active().equals("local")) {
        Project empty = new Project();
        empty.name = workspace.sessionName();
        history.reset(empty);
        typeCatalog.types.clear();
        render();
      }
      java.nio.file.Path draft = Paths.get(System.getProperty("studio.data"), "draft.sedona.json");
      if (Files.exists(draft) || Files.exists(draft.resolveSibling(draft.getFileName() + ".bak")))
        try {
          history.reset(ProjectStore.loadRecovering(draft));
          focus.folder = "0";
          focus.selected = null;
          projectFiles.dirty = false;
          render();
        } catch (Exception e) {
          notices.message(Tr.of("app.draftNotLoaded", e.getMessage()));
        }
    }
    paletteDock.filter();
    workspace.restoringSession = false;
    Platform.setImplicitExit(false);
    stage.show();
    savedView.install();
    savedView.restore();
    workspace.refreshSessionTabs();
    for (String arg : getParameters().getRaw())
      if (arg.startsWith("--ready-request=")) {
        String token = arg.substring("--ready-request=".length());
        if (token.matches("[0-9a-f-]{36}"))
          Files.writeString(
              SessionStore.directory(SessionStore.active()).resolve("ready-" + token), "ready");
      }
    deviceLink.timer =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "sedona-sample-clock");
              t.setDaemon(true);
              return t;
            });
    liveValues.scheduleSampleClock(1000);
    if (!getParameters().getRaw().contains("--smoke")) {
      String request =
          getParameters().getRaw().stream()
              .filter(a -> a.startsWith("--connect-request="))
              .map(a -> a.substring("--connect-request=".length()))
              .findFirst()
              .orElse(null);
      if (request != null)
        Platform.runLater(() -> deviceConnection.continueConfirmedConnection(request));
      else if (getParameters().getRaw().stream().noneMatch(a -> a.startsWith("--view-")))
        Platform.runLater(projectFiles::loadLocalPalette);
    }
    selfTest.runLaunchScenarios();
  }

  public void edit(Consumer<Project> change) {
    draftEdits.edit(change);
  }

  public void undo() {
    draftEdits.undo();
  }

  public void redo() {
    draftEdits.redo();
  }

  public void title() {
    stage.setTitle(
        "Sedona Studio Windows 0.7.0 ["
            + workspace.sessionName()
            + "]"
            + (projectFiles.dirty ? " • " : " — ")
            + history.get().name
            + (!deviceLink.session.connected()
                ? Tr.of("app.titleOffline")
                : simulator.running()
                    ? Tr.of("app.titleSimulator", simulator.port())
                    : Tr.of("app.titleDevice")));
  }

  public void render() {
    paletteDock.filter();
    title();
    if (!workspace.restoringSession && !getParameters().getRaw().contains("--smoke"))
      try {
        ProjectStore.save(
            history.get(), Paths.get(System.getProperty("studio.data"), "draft.sedona.json"));
      } catch (IOException e) {
        notices.message(Tr.of("app.draftNotSaved", e.getMessage()));
      }
    projectTree.refreshProjectSearch.run();
    projectTree.renderTree();
    renderCanvas();
    inspectorPane.renderInspector();
    List<String> problems = history.get().validate();
    checks.setText(problems.isEmpty() ? Tr.of("app.structureOk") : String.join("\n", problems));
    panel.publish();
  }

  public void renderCanvas() {
    diagramView.renderCanvas();
  }

  private ru.bccontrol.sedonastudio.toolchain.KitHelp kitHelp;
  private String kitHelpSdk;

  /** Help text of a component type from the kit sources of the chosen SDK; "" without them. */
  private String typeHelp(String type) {
    String chosen = prefs.get("sdkPath", "");
    if (kitHelp == null || !chosen.equals(kitHelpSdk)) {
      kitHelp =
          ru.bccontrol.sedonastudio.toolchain.KitHelp.of(
              ru.bccontrol.sedonastudio.toolchain.Sdk.find(chosen.isEmpty() ? null : chosen)
                  .orElse(null));
      kitHelpSdk = chosen;
    }
    return kitHelp.text(type);
  }

  public void colorsChanged() {
    diagramView.legend();
    renderCanvas();
  }

  public void fitDiagram() {
    diagramView.fitDiagram();
  }

  public void focusSelected() {
    diagramView.focusSelected();
  }

  public void renderTree() {
    projectTree.renderTree();
  }
}

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

/** Settings, help and theme. */
final class SettingsDialogs {
  private final ViewOptions view;
  private final LiveValues liveValues;
  private final EditorShell shell;
  String theme = "Arctic";

  SettingsDialogs(ViewOptions view, LiveValues liveValues, EditorShell shell) {
    this.view = view;
    this.liveValues = liveValues;
    this.shell = shell;
  }

  void settings() {
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.initOwner(shell.stage());
    dialog.setTitle(Tr.of("settings.title"));
    ComboBox<String> themes = new ComboBox<>();
    themes
        .getItems()
        .addAll("Arctic", "Sage", "Sand", "Lagoon", "Pearl", "Copper", "Blueprint", "Original");
    themes.setValue(theme);
    Spinner<Integer> gridStep = new Spinner<>(12, 96, view.gridStep, 12);
    Spinner<Double> gridWidth = new Spinner<>(0.5, 3.0, view.gridWidth, 0.5);
    Slider gridContrast = new Slider(0.03, 0.4, view.gridContrast);
    CheckBox grid = new CheckBox(Tr.of("settings.showGrid")),
        snap = new CheckBox(Tr.of("settings.snapGrid")),
        values = new CheckBox(Tr.of("settings.showValues"));
    // Which SDK the simulator, the kit workshop and code uploads use.
    TextField sdkPath = new TextField(shell.prefs().get("sdkPath", ""));
    sdkPath.setId("sdk-path");
    sdkPath.setPromptText(Tr.of("settings.sdkPrompt"));
    sdkPath.setPrefColumnCount(34);
    Label sdkState = new Label();
    sdkState.setWrapText(true);
    sdkState.setMaxWidth(520);
    Runnable describeSdk =
        () -> {
          String typed = sdkPath.getText().strip();
          java.util.Optional<ru.bccontrol.sedonastudio.toolchain.Sdk> found =
              typed.isEmpty()
                  ? ru.bccontrol.sedonastudio.toolchain.Sdk.find(null)
                  : ru.bccontrol.sedonastudio.toolchain.Sdk.at(java.nio.file.Paths.get(typed));
          sdkState.setText(
              found.isEmpty()
                  ? (typed.isEmpty() ? Tr.of("settings.sdkNone") : Tr.of("settings.sdkNotOne"))
                  : Tr.of(
                      "settings.sdkFound",
                      found.get().home,
                      found.get().platformId == null ? "?" : found.get().platformId,
                      found.get().vm == null
                          ? Tr.of("settings.sdkNoVm")
                          : found.get().vm.getFileName()));
        };
    sdkPath.textProperty().addListener((o, a, b) -> describeSdk.run());
    describeSdk.run();
    Button browseSdk = new Button(Tr.of("settings.sdkBrowse"));
    browseSdk.setOnAction(
        e -> {
          javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
          chooser.setTitle(Tr.of("settings.sdk"));
          java.io.File chosen = chooser.showDialog(dialog.getDialogPane().getScene().getWindow());
          if (chosen != null) sdkPath.setText(chosen.getPath());
        });
    CheckBox typeIcons = new CheckBox(Tr.of("annotation.showTypeIcons"));
    typeIcons.setId("show-type-icons");
    typeIcons.setSelected(TypeIcons.enabled);
    CheckBox canvasNotes = new CheckBox(Tr.of("annotation.showNotes"));
    canvasNotes.setId("show-canvas-notes");
    canvasNotes.setSelected(TypeIcons.showNotes);
    TextField iconsPath = new TextField(shell.prefs().get("iconsPath", ""));
    iconsPath.setId("icons-path");
    iconsPath.setPromptText(Tr.of("annotation.iconsPrompt"));
    Label iconsHint = new Label(Tr.of("annotation.iconsHint"));
    iconsHint.setWrapText(true);
    iconsHint.setMaxWidth(520);
    Label iconsError = new Label(TypeIcons.failure);
    iconsError.setWrapText(true);
    Button chooseIcons = new Button(Tr.of("annotation.browseIcons"));
    chooseIcons.setOnAction(
        e -> {
          javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
          chooser
              .getExtensionFilters()
              .add(new javafx.stage.FileChooser.ExtensionFilter("JAR / ZIP", "*.jar", "*.zip"));
          java.io.File selected =
              chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
          if (selected != null) iconsPath.setText(selected.getAbsolutePath());
        });
    // Colours of ports and links by the kind of value.
    String[] kindNames = {
      Tr.of("legend.bool"),
      Tr.of("legend.whole"),
      Tr.of("legend.real"),
      Tr.of("legend.text"),
      Tr.of("legend.other")
    };
    javafx.scene.control.ColorPicker[] pickers =
        new javafx.scene.control.ColorPicker[DiagramPane.KINDS.length];
    HBox colours = new HBox(10);
    for (int i = 0; i < pickers.length; i++) {
      pickers[i] =
          new javafx.scene.control.ColorPicker(DiagramPane.KIND_COLORS.get(DiagramPane.KINDS[i]));
      pickers[i].setId("color-" + DiagramPane.KINDS[i]);
      pickers[i].setStyle("-fx-color-label-visible: false;");
      colours.getChildren().add(new VBox(3, new Label(kindNames[i]), pickers[i]));
    }
    Button defaultColours = new Button(Tr.of("settings.coloursDefault"));
    defaultColours.setOnAction(
        e -> {
          for (int i = 0; i < pickers.length; i++)
            pickers[i].setValue(javafx.scene.paint.Color.web(DiagramPane.DEFAULT_COLORS[i]));
        });
    CheckBox autoApply = new CheckBox(Tr.of("settings.autoApply"));
    autoApply.setSelected(view.autoApply);
    autoApply.setId("auto-apply");
    Label autoApplyHint = new Label(Tr.of("settings.autoApplyHint"));
    autoApplyHint.setWrapText(true);
    autoApplyHint.setMaxWidth(520);
    CheckBox overlap = new CheckBox(Tr.of("settings.avoidOverlap"));
    overlap.setSelected(view.avoidOverlap);
    grid.setSelected(view.showGrid);
    snap.setSelected(view.snapGrid);
    values.setSelected(view.showValues);
    CheckBox zoomOptions = new CheckBox(Tr.of("settings.showZoom"));
    zoomOptions.setSelected(shell.prefs().getBoolean("showZoomTools", false));
    zoomOptions.setId("show-zoom-tools");
    CheckBox live = new CheckBox(Tr.of("settings.livePolling"));
    live.setSelected(shell.prefs().getBoolean("liveEnabled", true));
    TextField interval = new TextField(Integer.toString(liveValues.intervalMs()));
    interval.setId("polling-ms");
    interval.setPrefColumnCount(7);
    interval.setTextFormatter(
        new TextFormatter<String>(
            change -> change.getControlNewText().matches("[0-9]{0,5}") ? change : null));
    Label intervalError = new Label();
    intervalError.setStyle("-fx-text-fill: #b65d24;");
    // The language choice exists only when this installation has more than one language.
    List<Languages.Language> languages = Languages.available();
    ComboBox<Languages.Language> language = new ComboBox<>();
    language.getItems().addAll(languages);
    String currentLanguage =
        Languages.choose(
            System.getProperty("studio.lang"),
            shell.prefs().get("language", null),
            Locale.getDefault());
    languages.stream()
        .filter(l -> l.code().equals(currentLanguage))
        .findFirst()
        .ifPresent(language::setValue);
    language.setId("language");
    VBox languageBox =
        new VBox(
            6,
            new Label(Tr.of("settings.language")),
            language,
            new Label(Tr.of("settings.languageRestart")));
    languageBox.setVisible(languages.size() > 1);
    languageBox.setManaged(languages.size() > 1);
    VBox content =
        new VBox(
            12,
            languageBox,
            new Label(Tr.of("settings.appearance")),
            zoomOptions,
            new Label(Tr.of("settings.theme")),
            themes,
            grid,
            new Label(Tr.of("settings.gridStep")), gridStep,
            new Label(Tr.of("settings.gridWidth")), gridWidth,
            new Label(Tr.of("settings.gridContrast")), gridContrast,
            snap,
            values,
            overlap,
            typeIcons,
            canvasNotes,
            new Label(Tr.of("annotation.iconSource")),
            new Label(Tr.of("annotation.iconCount", TypeIcons.count())),
            new HBox(8, iconsPath, chooseIcons),
            iconsHint,
            iconsError,
            new Label(Tr.of("settings.colours")),
            colours,
            defaultColours,
            new Separator(),
            new Label(Tr.of("settings.sdk")),
            new HBox(8, sdkPath, browseSdk),
            sdkState,
            new Separator(),
            autoApply,
            autoApplyHint,
            new Separator(),
            live,
            new HBox(8, new Label(Tr.of("settings.interval")), interval),
            new Label(Tr.of("settings.intervalHint")),
            intervalError);
    content.setPadding(new Insets(15));
    dialog.getDialogPane().setContent(content);
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    Runnable validateInterval =
        () -> {
          try {
            PollingInterval.parse(interval.getText());
            intervalError.setText("");
            dialog.getDialogPane().lookupButton(ButtonType.OK).setDisable(false);
          } catch (IllegalArgumentException ex) {
            intervalError.setText(ex.getMessage());
            dialog.getDialogPane().lookupButton(ButtonType.OK).setDisable(true);
          }
        };
    interval.textProperty().addListener((o, a, b) -> validateInterval.run());
    validateInterval.run();
    if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
      shell.prefs().putBoolean("showZoomTools", zoomOptions.isSelected());
      shell.stage().getScene().lookup("#zoom-tools").setVisible(zoomOptions.isSelected());
      shell.prefs().putBoolean("liveEnabled", live.isSelected());
      int intervalMs = PollingInterval.parse(interval.getText());
      shell.prefs().putInt("liveIntervalMs", intervalMs);
      liveValues.configure(intervalMs, live.isSelected());
      if (languages.size() > 1 && language.getValue() != null)
        shell.prefs().put("language", language.getValue().code());
      theme = themes.getValue();
      if (sdkPath.getText().strip().isEmpty()) shell.prefs().remove("sdkPath");
      else shell.prefs().put("sdkPath", sdkPath.getText().strip());
      for (int i = 0; i < pickers.length; i++) {
        javafx.scene.paint.Color chosen = pickers[i].getValue();
        DiagramPane.KIND_COLORS.put(DiagramPane.KINDS[i], chosen);
        shell
            .prefs()
            .put(
                "color." + DiagramPane.KINDS[i],
                String.format(
                    "#%02x%02x%02x",
                    Math.round(chosen.getRed() * 255),
                    Math.round(chosen.getGreen() * 255),
                    Math.round(chosen.getBlue() * 255)));
      }
      shell.colorsChanged();
      view.autoApply = autoApply.isSelected();
      shell.prefs().putBoolean("autoApply", view.autoApply);
      view.avoidOverlap = overlap.isSelected();
      shell.prefs().putBoolean("avoidOverlap", view.avoidOverlap);
      view.showGrid = grid.isSelected();
      view.gridStep = gridStep.getValue(); view.gridWidth = gridWidth.getValue();
      view.gridContrast = gridContrast.getValue();
      shell.prefs().putInt("gridStep", view.gridStep);
      shell.prefs().putDouble("gridWidth", view.gridWidth);
      shell.prefs().putDouble("gridContrast", view.gridContrast);
      view.snapGrid = snap.isSelected();
      view.showValues = values.isSelected();
      shell.prefs().put("theme", theme);
      shell.prefs().putBoolean("showGrid", view.showGrid);
      shell.prefs().putBoolean("snapGrid", view.snapGrid);
      shell.prefs().putBoolean("showValues", view.showValues);
      shell.prefs().put("iconsPath", iconsPath.getText().strip());
      shell.prefs().putBoolean("typeIcons", typeIcons.isSelected());
      shell.prefs().putBoolean("canvasNotes", canvasNotes.isSelected());
      TypeIcons.configure(
          iconsPath.getText().strip(),
          typeIcons.isSelected(),
          canvasNotes.isSelected(),
          shell::render);
      applyTheme();
      shell.render();
    }
  }

  void help() {
    Alert dialog = new Alert(Alert.AlertType.INFORMATION);
    dialog.initOwner(shell.stage());
    dialog.setTitle(Tr.of("help.header"));
    dialog.setHeaderText(Tr.of("help.title"));
    dialog.setContentText(Tr.of("help.text"));
    dialog.showAndWait();
  }

  void applyTheme() {
    Map<String, String> colors = new HashMap<>();
    colors.put("Arctic", "#f3f6fa");
    colors.put("Sage", "#f0f5ef");
    colors.put("Sand", "#f8f4eb");
    colors.put("Lagoon", "#edf7f5");
    colors.put("Pearl", "#f8f7fa");
    colors.put("Copper", "#faf5f2");
    colors.put("Blueprint", "#f0f5fc");
    colors.put("Original", "#fffbea");
    String bg = colors.getOrDefault(theme, "#f3f6fa");
    shell.stage().getScene().getRoot().setStyle("-studio-sheet: " + bg + ";");
  }
}

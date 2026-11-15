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

/** Right-hand content: properties of the selected block or wire, with signal diagnostics. */
final class InspectorPane {
  private final History history;
  private final EditorFocus focus;
  private final DeviceLink deviceLink;
  private final LiveValues liveValues;
  private final EditActions editing;
  private final EditorShell shell;
  VBox content;

  Label wireSummary;

  InspectorPane(
      History history,
      EditorFocus focus,
      DeviceLink deviceLink,
      LiveValues liveValues,
      EditActions editing,
      EditorShell shell) {
    this.history = history;
    this.focus = focus;
    this.deviceLink = deviceLink;
    this.liveValues = liveValues;
    this.editing = editing;
    this.shell = shell;
  }

  private String endpointDescription(String title, Project.Node n, String port) {
    if (n == null) return Tr.of("inspector.componentMissing", title);
    Project.Port slot = n.port(port);
    String value = liveValues.fullValue(n, port);
    if (value.length() > 512) value = value.substring(0, 509) + "…";
    return Tr.of(
        "inspector.endpoint",
        title,
        n.name,
        port,
        n.id,
        n.type,
        (slot == null ? Tr.of("inspector.unknownType") : slot.type),
        value,
        liveValues.quality(n, port));
  }

  String signalDescription(Project.Wire w) {
    Project p = history.get();
    return endpointDescription(Tr.of("inspector.source"), p.nodes.get(w.source), w.sourcePort)
        + "\n\n"
        + endpointDescription(Tr.of("inspector.target"), p.nodes.get(w.target), w.targetPort);
  }

  void navigateWireEndpoint(Project.Wire wire, boolean source) {
    Project.Node n = history.get().nodes.get(source ? wire.source : wire.target);
    if (n == null) throw new IllegalArgumentException(Tr.of("inspector.componentNotFound"));
    focus.folder = n.parent;
    shell.selectGroup(Set.of(n.id));
    shell.render();
    shell.focusSelected();
  }

  void refreshWireSummary() {
    if (wireSummary != null && focus.selectedWire != null)
      wireSummary.setText(signalDescription(focus.selectedWire));
  }

  void renderInspector() {
    content.getChildren().clear();
    liveValues.clearValueLabels();
    wireSummary = null;
    if (focus.selectedWire != null) {
      Project.Wire wire = focus.selectedWire;
      wireSummary = new Label(signalDescription(wire));
      wireSummary.setWrapText(true);
      wireSummary.setMaxWidth(Double.MAX_VALUE);
      Button
          source =
              shell.button(Tr.of("inspector.toSource"), () -> navigateWireEndpoint(wire, true)),
          target =
              shell.button(Tr.of("inspector.toTarget"), () -> navigateWireEndpoint(wire, false));
      Label timing = new Label(Tr.of("inspector.separateReads"));
      timing.setWrapText(true);
      timing.setStyle("-fx-text-fill: #718391;");
      source.setId("wire-go-source");
      target.setId("wire-go-target");
      content
          .getChildren()
          .addAll(
              new Label(Tr.of("inspector.linkDiagnostics")),
              wireSummary,
              timing,
              new HBox(6, source, target),
              shell.button(Tr.of("inspector.removeLink"), editing::remove));
      return;
    }
    if (focus.selectedIds().size() > 1) {
      content
          .getChildren()
          .addAll(
              new Label(Tr.of("inspector.selectedCount", focus.selectedIds().size())),
              shell.button(Tr.of("inspector.copy"), editing::copySelection),
              shell.button(Tr.of("inspector.duplicateWithLinks"), editing::duplicate),
              shell.button(Tr.of("inspector.removeSelected"), editing::remove),
              shell.button(Tr.of("inspector.moveToFolder"), editing::moveToFolder));
      return;
    }
    Project.Node n = history.get().nodes.get(focus.selected);
    if (n == null) {
      Label hint = new Label(Tr.of("inspector.hint"));
      hint.setWrapText(true);
      content.getChildren().add(hint);
      return;
    }
    Label type = new Label(n.type + "\n" + n.kitPart);
    type.setWrapText(true);
    type.setGraphic(TypeIcons.graphic(n, 20));
    TextField name = new TextField(n.name);
    content
        .getChildren()
        .addAll(
            new Label(Tr.of("inspector.blockTitle", n.id)),
            type,
            new Label(Tr.of("inspector.name")),
            name,
            shell.button(
                Tr.of("inspector.rename"),
                () -> shell.edit(p -> p.nodes.get(n.id).name = name.getText().trim())));
    TextArea note = new TextArea(n.note);
    note.setPromptText(Tr.of("inspector.note"));
    note.setPrefRowCount(2);
    note.setWrapText(true);
    content
        .getChildren()
        .addAll(
            note,
            shell.button(
                Tr.of("inspector.saveNote"),
                () ->
                    shell.edit(
                        p -> {
                          if (note.getText().length() > 4000)
                            throw new IllegalArgumentException(Tr.of("store.noteTooLong"));
                          p.nodes.get(n.id).note = note.getText();
                        })));
    String helpText = TypeIcons.description.apply(n.type);
    Label description =
        new Label(helpText.isBlank() ? Tr.of("annotation.noDescription") : helpText);
    description.setWrapText(true);
    TitledPane help = new TitledPane(Tr.of("annotation.typeDescription"), description);
    help.setExpanded(false);
    help.setId("type-description");
    content.getChildren().add(help);
    // How the block looks; kept in the project only, never sent to a device.
    CheckBox info = new CheckBox(Tr.of("inspector.showInfo"));
    info.setSelected(n.info);
    info.setId("block-info");
    info.setOnAction(e -> shell.edit(p -> p.nodes.get(n.id).info = info.isSelected()));
    record InfoChoice(String code, String label) {
      public String toString() {
        return label;
      }
    }
    ComboBox<InfoChoice> source = new ComboBox<>();
    source
        .getItems()
        .addAll(
            new InfoChoice("comment", Tr.of("annotation.instanceComment")),
            new InfoChoice("type", Tr.of("annotation.typeDescription")));
    source.setId("block-info-source");
    String chosen =
        n.infoSource.equals("auto") ? (n.note.isBlank() ? "type" : "comment") : n.infoSource;
    source.setValue(
        source.getItems().stream()
            .filter(x -> x.code().equals(chosen))
            .findFirst()
            .orElse(source.getItems().get(0)));
    source.setOnAction(
        e -> {
          if (source.getValue() != null)
            shell.edit(p -> p.nodes.get(n.id).infoSource = source.getValue().code());
        });
    ComboBox<String> icon = new ComboBox<>();
    icon.getItems().add("");
    icon.getItems().addAll(DiagramPane.ICONS);
    icon.setValue(n.icon);
    icon.setId("block-icon");
    icon.setPromptText(Tr.of("inspector.noIcon"));
    icon.setOnAction(
        e ->
            shell.edit(
                p -> p.nodes.get(n.id).icon = icon.getValue() == null ? "" : icon.getValue()));
    Button standardWidth = new Button(Tr.of("inspector.standardWidth"));
    standardWidth.setDisable(n.width <= 0);
    standardWidth.setOnAction(e -> shell.edit(p -> p.nodes.get(n.id).width = 0));
    content
        .getChildren()
        .addAll(
            info,
            source,
            new HBox(8, new Label(Tr.of("annotation.marker")), icon),
            standardWidth,
            lookHint());
    for (Project.Port port : n.ports) {
      Label label = new Label(port.name + " · " + port.type + (port.readonly ? " · RO" : "")),
          live =
              new Label(
                  (deviceLink.baseline == null
                          ? Tr.of("inspector.projectValue")
                          : Tr.of("inspector.cacheValue"))
                      + liveValues.display(n, port.name));
      liveValues.showValueIn(port.name, live);
      TextField field = new TextField(n.values.getOrDefault(port.name, ""));
      field.setDisable(
          port.readonly
              || !(port.asStr
                  || port.type.matches("sys::(bool|byte|short|int|long|float|double)")));
      Button update =
          shell.button(
              Tr.of("inspector.change"),
              () -> {
                String literal =
                    port.asStr
                        ? ScalarValues.validateText(field.getText(), port.textMax)
                        : ScalarValues.validateLiteral(port.type, field.getText());
                shell.edit(p -> p.nodes.get(n.id).values.put(port.name, literal));
              });
      update.setDisable(field.isDisable());
      HBox actions =
          new HBox(
              6, update, shell.button(Tr.of("inspector.toTrend"), () -> liveValues.watch(n, port)));
      content.getChildren().addAll(label, field, live, actions, new Separator());
    }
  }

  private Label lookHint() {
    Label hint = new Label(Tr.of("inspector.lookHint"));
    hint.setWrapText(true);
    return hint;
  }
}

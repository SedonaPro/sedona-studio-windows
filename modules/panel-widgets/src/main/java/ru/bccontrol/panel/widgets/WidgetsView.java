package ru.bccontrol.panel.widgets;

import static ru.bccontrol.panel.api.PanelData.*;

import java.util.*;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import ru.bccontrol.panel.api.*;

final class WidgetsView implements PanelModule.PanelView {
  private final PanelHost host;
  private final VBox root = new VBox(10), tiles = new VBox(8);
  private final Label status = new Label();
  private final List<PanelDocument.Widget> widgets = new ArrayList<>();
  private final Map<Label, PanelDocument.Widget> values = new HashMap<>();
  private final Map<Label, WidgetFace> faces = new HashMap<>();
  private final TextField minimum = new TextField("0"), maximum = new TextField("100"), unit = new TextField();
  private final WidgetFace preview = new WidgetFace(220, 100);
  private static final List<String> KINDS = List.of("value", "indicator", "bar", "gauge", "thermometer", "tank", "damper", "fan", "pump", "bipolar");
  private static boolean binary(String code) { return code.equals("indicator") || code.equals("pump"); }
  private String selectedKind() { return KINDS.get(Math.max(0, kind.getSelectionModel().getSelectedIndex())); }
  private static String kindName(String code) {
    return switch(code) {
      case "tank" -> PanelText.of("panelmodule.kindTank", "Tank level");
      case "damper" -> PanelText.of("panelmodule.kindDamper", "Damper position");
      case "fan" -> PanelText.of("panelmodule.kindFan", "Fan speed");
      case "pump" -> PanelText.of("panelmodule.kindPump", "Pump state");
      case "bipolar" -> PanelText.of("panelmodule.kindBipolar", "Deviation from zero");
      case "indicator" -> PanelText.of("panelmodule.kindIndicator", "Indicator");
      case "bar" -> PanelText.of("panelmodule.kindBar", "Linear scale");
      case "gauge" -> PanelText.of("panelmodule.kindGauge", "Dial gauge");
      case "thermometer" -> PanelText.of("panelmodule.kindThermometer", "Thermometer");
      default -> PanelText.of("panelmodule.kindValue", "Value");
    };
  }
  private ListCell<String> kindCell() {
    return new ListCell<>() {
      protected void updateItem(String text, boolean empty) {
        super.updateItem(text, empty); setText(empty ? null : text); setGraphic(null);
        if (!empty) { WidgetFace thumb = new WidgetFace(88, 46);
          thumb.draw(KINDS.get(kind.getItems().indexOf(text)), "42.5", 42.5, 0, 100, "", Quality.GOOD);
          setGraphic(thumb); }
      }
    };
  }
  private final ComboBox<Component> components = new ComboBox<>();
  private final ComboBox<String> fields = new ComboBox<>(), kind = new ComboBox<>();
  private final CheckBox edit = new CheckBox(PanelText.of("panelmodule.setup", "Panel setup"));
  private final PanelHost.Subscription subscription;
  private boolean closed, broken;
  private final Set<Ref> booleanFields = new HashSet<>();

  WidgetsView(PanelHost host) {
    this.host = host;
    root.setPadding(new Insets(12));
    root.setId("independent-panel");
    status.setWrapText(true);
    try {
      widgets.addAll(PanelDocument.read(host.loadDocument()));
    } catch (Exception ex) {
      broken = true;
      status.setText(PanelText.of("panelmodule.notRead", "Panel not read: {0}", ex.getMessage()));
    }
    for (String code : KINDS) kind.getItems().add(kindName(code));
    kind.setCellFactory(list -> kindCell()); kind.setButtonCell(kindCell());
    kind.getSelectionModel().selectFirst(); kind.setId("panel-widget-kind");
    kind.valueProperty().addListener((o,a,b) -> {
      if (selectedKind().equals("bipolar") && minimum.getText().equals("0")) minimum.setText("-100");
      updatePreview();
    });
    minimum.textProperty().addListener((o,a,b) -> updatePreview());
    maximum.textProperty().addListener((o,a,b) -> updatePreview());
    unit.textProperty().addListener((o,a,b) -> updatePreview());
    updatePreview();
    components.setMaxWidth(Double.MAX_VALUE);
    fields.setMaxWidth(Double.MAX_VALUE);
    fields.setId("panel-widget-field");
    components
        .valueProperty()
        .addListener(
            (o, a, b) -> {
              fields.getItems().clear();
              if (b != null)
                for (Field f : b.fields()) if (f.readable()) fields.getItems().add(f.name());
              if (!fields.getItems().isEmpty()) fields.getSelectionModel().selectFirst();
            });
    Button add = new Button(PanelText.of("panelmodule.addWidget", "Add widget"));
    add.setId("panel-add-widget");
    add.setOnAction(
        e -> {
          Component c = components.getValue();
          String f = fields.getValue();
          if (c == null || f == null || broken) return;
          if (binary(selectedKind())
              && c.fields().stream()
                  .noneMatch(
                      field ->
                          field.name().equals(f)
                              && (field.type().equals("bool")
                                  || field.type().equals("sys::bool")))) {
            status.setText(
                PanelText.of(
                    "panelmodule.indicatorNeedsBoolean", "An indicator needs a Boolean field"));
            return;
          }
          if (widgets.size() >= 32) {
            status.setText(PanelText.of("panelmodule.widgetLimit", "No more than 32 widgets"));
            return;
          }
          double lo, hi;
          try { lo = Double.parseDouble(minimum.getText().trim()); hi = Double.parseDouble(maximum.getText().trim());
            if (!Double.isFinite(lo) || !Double.isFinite(hi) || hi <= lo || (selectedKind().equals("bipolar") && !(lo < 0 && hi > 0)) || !Double.isFinite(hi-lo) || unit.getText().length()>32)
              throw new IllegalArgumentException();
          } catch (RuntimeException ex) {
            status.setText(PanelText.of("panelmodule.badRange", "Use finite limits: maximum must exceed minimum")); return;
          }
          if (!selectedKind().equals("value") && !binary(selectedKind()) && c.fields().stream().noneMatch(field ->
              field.name().equals(f) && field.type().matches("(sys::)?(byte|short|int|long|float|double)"))) {
            status.setText(PanelText.of("panelmodule.numericField", "A scale needs a numeric field")); return;
          }
          widgets.add(new PanelDocument.Widget(selectedKind(), c.name() + " · " + f,
              new Ref(c.path(), f), lo, hi, unit.getText()));
          save();
          render();
          update(host.snapshot());
        });
    VBox config =
        new VBox(
            7,
            new Label(PanelText.of("panelmodule.component", "Component")),
            components,
            new Label(PanelText.of("panelmodule.field", "Field")),
            fields,
            kind, preview,
            new Label(PanelText.of("panelmodule.range", "Minimum / maximum / unit")),
            new HBox(5, minimum, maximum, unit), add);
    config.visibleProperty().bind(edit.selectedProperty());
    config.managedProperty().bind(edit.selectedProperty());
    edit.setSelected(widgets.isEmpty());
    ScrollPane scroll = new ScrollPane(tiles);
    scroll.setFitToWidth(true);
    VBox.setVgrow(scroll, Priority.ALWAYS);
    root.getChildren().addAll(edit, config, status, scroll);
    render();
    subscription = host.subscribe(this::update);
  }

  private void updatePreview() {
    double lo=0, hi=100;
    try { lo=Double.parseDouble(minimum.getText()); hi=Double.parseDouble(maximum.getText());
      if (!Double.isFinite(lo) || !Double.isFinite(hi) || hi<=lo || !Double.isFinite(hi-lo)) {lo=0;hi=100;}
    } catch(RuntimeException ex) {lo=0;hi=100;}
    double value=lo+(hi-lo)*0.425;
    preview.draw(selectedKind(), binary(selectedKind()) ? PanelText.of("panelmodule.on", "● On") : String.format(java.util.Locale.ROOT,"%.1f", value),
        value,lo,hi,unit.getText(),Quality.GOOD);
  }

  private void save() {
    try {
      host.saveDocument(PanelDocument.write(widgets));
      status.setText(PanelText.of("panelmodule.saved", "Panel saved"));
    } catch (Exception ex) {
      status.setText(PanelText.of("panelmodule.notSaved", "Could not save: {0}", ex.getMessage()));
    }
  }

  private void render() {
    tiles.getChildren().clear();
    values.clear(); faces.clear();
    for (PanelDocument.Widget w : widgets) {
      Label title = new Label(w.title()), value = new Label("—");
      value.setId("panel-value-" + values.size());
      WidgetFace face = new WidgetFace(220, 110);
      faces.put(value, face);
      value.setStyle("-fx-font-size: 11px;");
      value.setWrapText(true);
      Button remove = new Button(PanelText.of("panelmodule.remove", "Remove"));
      remove.visibleProperty().bind(edit.selectedProperty());
      remove.managedProperty().bind(edit.selectedProperty());
      remove.setOnAction(
          e -> {
            widgets.remove(w);
            save();
            render();
            update(host.snapshot());
          });
      VBox tile =
          new VBox(
              6,
              title, face,
              value,
              new Label(w.ref().componentPath() + " · " + w.ref().field()),
              remove);
      tile.setPadding(new Insets(10));
      tile.setStyle(
          "-fx-background-color: white; -fx-background-radius: 12; -fx-border-color: #d5e2eb; -fx-border-radius: 12;");
      tiles.getChildren().add(tile);
      values.put(value, w);
    }
    host.watch(new HashSet<>(widgets.stream().map(PanelDocument.Widget::ref).toList()));
  }

  private void update(Snapshot snapshot) {
    if (closed) return;
    String selected = components.getValue() == null ? null : components.getValue().path();
    if (!components.getItems().equals(snapshot.components())) {
      booleanFields.clear();
      for (Component component : snapshot.components())
        for (Field field : component.fields())
          if (field.type().equals("bool") || field.type().equals("sys::bool"))
            booleanFields.add(new Ref(component.path(), field.name()));
      components.getItems().setAll(snapshot.components());
      components.getItems().stream()
          .filter(c -> c.path().equals(selected))
          .findFirst()
          .ifPresentOrElse(
              components::setValue, () -> components.getSelectionModel().selectFirst());
    }
    for (var entry : values.entrySet()) {
      Sample sample = snapshot.values().get(entry.getValue().ref());
      Label label = entry.getKey();
      PanelDocument.Widget widget = entry.getValue();
      WidgetFace face = faces.get(label);
      double number = Double.NaN;
      if (sample != null) try { number = binary(widget.kind())
          ? (sample.value().equalsIgnoreCase("true") || sample.value().equals("1") ? 1 : 0)
          : Double.parseDouble(sample.value()); } catch (RuntimeException ignored) {}
      boolean compatible = !binary(widget.kind()) || booleanFields.contains(widget.ref());
      face.draw(widget.kind(), sample == null || !compatible ? "—" : sample.value(),
          compatible ? number : Double.NaN, widget.min(), widget.max(), widget.unit(),
          sample == null ? Quality.ERROR : sample.quality());
      if (sample == null) {
        label.setText(PanelText.of("panelmodule.unavailable", "Unavailable"));
        label.setStyle("-fx-font-size: 12px; -fx-text-fill: #956427;");
        continue;
      }
      if (binary(entry.getValue().kind())
          && !booleanFields.contains(entry.getValue().ref())) {
        label.setText(PanelText.of("panelmodule.incompatibleType", "Incompatible type"));
        label.setStyle("-fx-font-size: 12px; -fx-text-fill: #956427;");
        continue;
      }
      String text =
          binary(entry.getValue().kind())
              ? (sample.value().equalsIgnoreCase("true") || sample.value().equals("1")
                  ? PanelText.of("panelmodule.on", "● On")
                  : PanelText.of("panelmodule.off", "○ Off"))
              : sample.value();
      label.setText(text);
      label.setStyle(
          "-fx-font-size: 12px; -fx-text-fill: "
              + (sample.quality() == Quality.GOOD ? "#287e73" : "#956427")
              + ";");
      label.setTooltip(
          new Tooltip(
              switch (sample.quality()) {
                    case GOOD -> PanelText.of("panelmodule.fresh", "Fresh");
                    case STALE -> PanelText.of("panelmodule.stale", "Stale");
                    case OFFLINE -> PanelText.of("panelmodule.offline", "Offline");
                    case LOCAL -> PanelText.of("panelmodule.local", "Local");
                    case ERROR -> PanelText.of("panelmodule.error", "Error");
                  }
                  + " · "
                  + sample.detail()));
    }
    if (!broken)
      status.setText(
          snapshot.connected()
              ? PanelText.of("panelmodule.connected", "Connected")
              : PanelText.of("panelmodule.offlineSaved", "Offline · saved data is shown"));
  }

  public Node content() {
    return root;
  }

  public void close() {
    if (closed) return;
    closed = true;
    subscription.close();
    host.watch(Set.of());
  }
}

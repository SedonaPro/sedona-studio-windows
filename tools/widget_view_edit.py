from pathlib import Path
r=Path(__file__).resolve().parents[1];p=r/'modules/panel-widgets/src/main/java/ru/bccontrol/panel/widgets/WidgetsView.java';s=p.read_text(encoding='utf-8')
s=s.replace('  private final Map<Label, PanelDocument.Widget> values = new HashMap<>();','''  private final Map<Label, PanelDocument.Widget> values = new HashMap<>();
  private final Map<Label, WidgetFace> faces = new HashMap<>();
  private final TextField minimum = new TextField("0"), maximum = new TextField("100"), unit = new TextField();
  private final WidgetFace preview = new WidgetFace(220, 100);
  private static final List<String> KINDS = List.of("value", "indicator", "bar", "gauge", "thermometer");
  private String selectedKind() { return KINDS.get(Math.max(0, kind.getSelectionModel().getSelectedIndex())); }
  private static String kindName(String code) {
    return switch(code) {
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
  }''')
a=s.index('    kind.getItems()');b=s.index('    components.setMaxWidth',a)
s=s[:a]+'''    for (String code : KINDS) kind.getItems().add(kindName(code));
    kind.setCellFactory(list -> kindCell()); kind.setButtonCell(kindCell());
    kind.getSelectionModel().selectFirst(); kind.setId("panel-widget-kind");
    kind.valueProperty().addListener((o,a,b) -> updatePreview());
    minimum.textProperty().addListener((o,a,b) -> updatePreview());
    maximum.textProperty().addListener((o,a,b) -> updatePreview());
    unit.textProperty().addListener((o,a,b) -> updatePreview());
    updatePreview();
'''+s[b:]
s=s.replace('kind.getValue().equals(PanelText.of("panelmodule.kindIndicator", "Indicator"))','selectedKind().equals("indicator")')
a=s.index('          widgets.add(');b=s.index('          save();',a)
s=s[:a]+'''          double lo, hi;
          try { lo = Double.parseDouble(minimum.getText().trim()); hi = Double.parseDouble(maximum.getText().trim());
            if (!Double.isFinite(lo) || !Double.isFinite(hi) || hi <= lo || !Double.isFinite(hi-lo) || unit.getText().length()>32)
              throw new IllegalArgumentException();
          } catch (RuntimeException ex) {
            status.setText(PanelText.of("panelmodule.badRange", "Use finite limits: maximum must exceed minimum")); return;
          }
          if (!Set.of("value", "indicator").contains(selectedKind()) && c.fields().stream().noneMatch(field ->
              field.name().equals(f) && field.type().matches("(sys::)?(byte|short|int|long|float|double)"))) {
            status.setText(PanelText.of("panelmodule.numericField", "A scale needs a numeric field")); return;
          }
          widgets.add(new PanelDocument.Widget(selectedKind(), c.name() + " · " + f,
              new Ref(c.path(), f), lo, hi, unit.getText()));
'''+s[b:]
s=s.replace('            kind,\n            add);','''            kind, preview,
            new Label(PanelText.of("panelmodule.range", "Minimum / maximum / unit")),
            new HBox(5, minimum, maximum, unit), add);''')
s=s.replace('    values.clear();','    values.clear(); faces.clear();')
s=s.replace('      value.setStyle("-fx-font-size: 22px;");','''      WidgetFace face = new WidgetFace(220, 110);
      faces.put(value, face);
      value.setStyle("-fx-font-size: 11px;");''')
s=s.replace('              title,\n              value,','              title, face,\n              value,')
s=s.replace('"-fx-background-color: #f3f7f8; -fx-border-color: #c4d4da; -fx-border-radius: 6;"','"-fx-background-color: white; -fx-background-radius: 12; -fx-border-color: #d5e2eb; -fx-border-radius: 12;"')
s=s.replace('      if (sample == null) {','''      PanelDocument.Widget widget = entry.getValue();
      WidgetFace face = faces.get(label);
      double number = Double.NaN;
      if (sample != null) try { number = widget.kind().equals("indicator")
          ? (sample.value().equalsIgnoreCase("true") || sample.value().equals("1") ? 1 : 0)
          : Double.parseDouble(sample.value()); } catch (RuntimeException ignored) {}
      boolean compatible = !widget.kind().equals("indicator") || booleanFields.contains(widget.ref());
      face.draw(widget.kind(), sample == null || !compatible ? "—" : sample.value(),
          compatible ? number : Double.NaN, widget.min(), widget.max(), widget.unit(),
          sample == null ? Quality.ERROR : sample.quality());
      if (sample == null) {''')
# Keep full value/status in the accessible text beneath the face.
s=s.replace('-fx-font-size: 20px;', '-fx-font-size: 12px;').replace('-fx-font-size: 22px;', '-fx-font-size: 12px;')
s=s.replace('  private void save() {','''  private void updatePreview() {
    double lo=0, hi=100;
    try { lo=Double.parseDouble(minimum.getText()); hi=Double.parseDouble(maximum.getText());
      if (!Double.isFinite(lo) || !Double.isFinite(hi) || hi<=lo || !Double.isFinite(hi-lo)) {lo=0;hi=100;}
    } catch(RuntimeException ex) {lo=0;hi=100;}
    double value=lo+(hi-lo)*0.425;
    preview.draw(selectedKind(), selectedKind().equals("indicator") ? "ON" : String.format(java.util.Locale.ROOT,"%.1f", value),
        value,lo,hi,unit.getText(),Quality.GOOD);
  }

  private void save() {''')
p.write_text(s,encoding='utf-8')
new={'kindBar':('Linear scale','Линейная шкала'),'kindGauge':('Dial gauge','Круговой прибор'),'kindThermometer':('Thermometer','Термометр'),'range':('Minimum / maximum / unit','Минимум / максимум / единица'),'badRange':('Use finite limits: maximum must exceed minimum','Задайте конечные границы: максимум должен быть больше минимума'),'numericField':('A scale needs a numeric field','Для шкалы нужно числовое поле')}
for i,lang in enumerate(['en','ru']):
 p=r/f'lang/{lang}.properties';p.write_text(p.read_text(encoding='utf-8')+'\n'+''.join('panelmodule.'+k+'='+v[i]+'\n' for k,v in new.items()),encoding='utf-8')

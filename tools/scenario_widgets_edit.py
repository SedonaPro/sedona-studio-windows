from pathlib import Path
r=Path(__file__).resolve().parents[1]
base=r/'modules/panel-widgets/src/main/java/ru/bccontrol/panel/widgets'
for name in ['WidgetsView.java','PanelDocument.java']:
 p=base/name;s=p.read_text(encoding='utf-8');s=s.replace('"value", "indicator", "bar", "gauge", "thermometer"','"value", "indicator", "bar", "gauge", "thermometer", "tank", "damper", "fan", "pump", "bipolar"')
 if name=='WidgetsView.java':
  s=s.replace('  private String selectedKind()', '  private static boolean binary(String code) { return code.equals("indicator") || code.equals("pump"); }\n  private String selectedKind()')
  s=s.replace('selectedKind().equals("indicator")','binary(selectedKind())')
  s=s.replace('widget.kind().equals("indicator")','binary(widget.kind())')
  s=s.replace('entry.getValue().kind().equals("indicator")','binary(entry.getValue().kind())')
  s=s.replace('!Set.of("value", "indicator").contains(selectedKind())','!selectedKind().equals("value") && !binary(selectedKind())')
  s=s.replace('kind.valueProperty().addListener((o,a,b) -> updatePreview());','''kind.valueProperty().addListener((o,a,b) -> {
      if (selectedKind().equals("bipolar") && minimum.getText().equals("0")) minimum.setText("-100");
      updatePreview();
    });''')
  s=s.replace('hi <= lo || !Double.isFinite(hi-lo)', 'hi <= lo || (selectedKind().equals("bipolar") && !(lo < 0 && hi > 0)) || !Double.isFinite(hi-lo)')
  s=s.replace('case "indicator" ->', '''case "tank" -> PanelText.of("panelmodule.kindTank", "Tank level");
      case "damper" -> PanelText.of("panelmodule.kindDamper", "Damper position");
      case "fan" -> PanelText.of("panelmodule.kindFan", "Fan speed");
      case "pump" -> PanelText.of("panelmodule.kindPump", "Pump state");
      case "bipolar" -> PanelText.of("panelmodule.kindBipolar", "Deviation from zero");
      case "indicator" ->''')
  s=s.replace('binary(selectedKind()) ? "ON"', 'binary(selectedKind()) ? PanelText.of("panelmodule.on", "● On")')
 p.write_text(s,encoding='utf-8')
texts={'kindTank':('Tank level','Уровень бака'),'kindDamper':('Damper position','Положение заслонки'),'kindFan':('Fan speed','Скорость вентилятора'),'kindPump':('Pump state','Состояние насоса'),'kindBipolar':('Deviation from zero','Отклонение от нуля')}
for i,lang in enumerate(['en','ru']):
 p=r/f'lang/{lang}.properties';p.write_text(p.read_text(encoding='utf-8')+'\n'+''.join('panelmodule.'+k+'='+v[i]+'\n' for k,v in texts.items()),encoding='utf-8')
p=r/'modules/panel-demo/src/main/java/ru/bccontrol/panel/demo/DemoApp.java';s=p.read_text(encoding='utf-8').replace('kinds.getItems().size() != 5','kinds.getItems().size() != 10').replace('new int[] {2, 3, 4}','new int[] {2, 3, 4, 5, 6, 7, 9}').replace('size() != 4','size() != 8').replace('#panel-value-3','#panel-value-7');p.write_text(s,encoding='utf-8')

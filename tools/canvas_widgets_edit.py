from pathlib import Path
r=Path(__file__).resolve().parents[1]
def edit(path,a,b):
 p=r/path;s=p.read_text(encoding='utf-8');assert a in s,(path,a);p.write_text(s.replace(a,b),encoding='utf-8')
ui='src/main/java/ru/bccontrol/sedonastudio/ui/'
edit(ui+'ViewOptions.java','  boolean showGrid = true;','  boolean showGrid = true;\n  int gridStep = 24;\n  double gridWidth = 1, gridContrast = 0.12;')
edit(ui+'StudioApp.java','    view.showGrid = prefs.getBoolean("showGrid", true);','    view.showGrid = prefs.getBoolean("showGrid", true);\n    view.gridStep = Math.max(12, Math.min(96, prefs.getInt("gridStep", 24)));\n    view.gridWidth = Math.max(0.5, Math.min(3, prefs.getDouble("gridWidth", 1)));\n    view.gridContrast = Math.max(0.03, Math.min(0.4, prefs.getDouble("gridContrast", 0.12)));')
edit(ui+'DiagramView.java','    shell.world().setStyle(view.showGrid ? "" : "-fx-background-image: none;");','''    if (!view.showGrid) shell.world().setStyle("-fx-background-image: none;");
    else {
      int step = view.gridStep;
      double width = view.gridWidth;
      javafx.scene.image.WritableImage tile = new javafx.scene.image.WritableImage(step * 5, step * 5);
      for (int y = 0; y < step * 5; y++) for (int x = 0; x < step * 5; x++) {
        boolean major = x < width || y < width;
        boolean minor = x % step < width || y % step < width;
        tile.getPixelWriter().setColor(x, y, javafx.scene.paint.Color.color(0.22, 0.43, 0.56,
            major ? view.gridContrast : minor ? view.gridContrast * 0.45 : 0));
      }
      shell.world().setStyle("-fx-background-image: none;");
      shell.world().setBackground(new javafx.scene.layout.Background(
          new javafx.scene.layout.BackgroundFill(javafx.scene.paint.Color.TRANSPARENT, null, null),
          new javafx.scene.layout.BackgroundImage(tile, javafx.scene.layout.BackgroundRepeat.REPEAT,
              javafx.scene.layout.BackgroundRepeat.REPEAT, javafx.scene.layout.BackgroundPosition.DEFAULT,
              javafx.scene.layout.BackgroundSize.DEFAULT)));
    }''')
# Keep the theme background fill supplied by CSS: the tile is installed as an image URL instead below.
edit(ui+'SettingsDialogs.java','    CheckBox grid =','''    Spinner<Integer> gridStep = new Spinner<>(12, 96, view.gridStep, 12);
    Spinner<Double> gridWidth = new Spinner<>(0.5, 3.0, view.gridWidth, 0.5);
    Slider gridContrast = new Slider(0.03, 0.4, view.gridContrast);
    CheckBox grid =''')
edit(ui+'SettingsDialogs.java','            grid,\n            snap,','''            grid,
            new Label(Tr.of("settings.gridStep")), gridStep,
            new Label(Tr.of("settings.gridWidth")), gridWidth,
            new Label(Tr.of("settings.gridContrast")), gridContrast,
            snap,''')
edit(ui+'SettingsDialogs.java','      view.showGrid = grid.isSelected();','''      view.showGrid = grid.isSelected();
      view.gridStep = gridStep.getValue(); view.gridWidth = gridWidth.getValue();
      view.gridContrast = gridContrast.getValue();
      shell.prefs().putInt("gridStep", view.gridStep);
      shell.prefs().putDouble("gridWidth", view.gridWidth);
      shell.prefs().putDouble("gridContrast", view.gridContrast);''')
for lang,lines in [('en','settings.gridStep=Canvas grid spacing (px)\nsettings.gridWidth=Canvas grid line width (px)\nsettings.gridContrast=Canvas grid contrast\n'),('ru','settings.gridStep=Шаг сетки холста (пикс.)\nsettings.gridWidth=Толщина линий сетки (пикс.)\nsettings.gridContrast=Контраст сетки холста\n')]:
 p=r/f'lang/{lang}.properties';p.write_text(p.read_text(encoding='utf-8')+'\n'+lines,encoding='utf-8')

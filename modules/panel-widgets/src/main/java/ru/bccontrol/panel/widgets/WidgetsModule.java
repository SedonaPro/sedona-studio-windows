package ru.bccontrol.panel.widgets;

import ru.bccontrol.panel.api.*;

public final class WidgetsModule implements PanelModule {
  public String id() {
    return "bccontrol.widgets";
  }

  public String title() {
    return PanelText.of("panelmodule.title", "Panel");
  }

  public int apiMajor() {
    return API_MAJOR;
  }

  public PanelView create(PanelHost host) {
    return new WidgetsView(host);
  }
}

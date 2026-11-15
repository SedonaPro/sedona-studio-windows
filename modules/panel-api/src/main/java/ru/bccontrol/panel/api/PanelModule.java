package ru.bccontrol.panel.api;

import javafx.scene.Node;

/** ServiceLoader entry point. API major must match before create() is called. */
public interface PanelModule {
  int API_MAJOR = 1;

  String id();

  String title();

  int apiMajor();

  PanelView create(PanelHost host);

  interface PanelView extends AutoCloseable {
    Node content();

    void close();
  }
}

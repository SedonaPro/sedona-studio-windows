package ru.bccontrol.sedonastudio.ui;

import java.nio.file.*;
import java.util.*;
import javafx.scene.control.*;
import ru.bccontrol.panel.api.PanelData;
import ru.bccontrol.sedonastudio.integration.panel.*;
import ru.bccontrol.sedonastudio.model.History;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.storage.*;

/**
 * Optional "Panel" tab. Loads panel modules from separate JARs and feeds them read-only snapshots
 * of the project and of live values; it never writes to the project or to a device.
 */
final class PanelFeature {
  private final History history;
  private final DeviceLink deviceLink;
  private final LiveValues liveValues;
  PanelBridge host;
  PanelModules module;
  private final PanelSnapshots snapshots = new PanelSnapshots();
  private int pollingCursor;

  PanelFeature(History history, DeviceLink deviceLink, LiveValues liveValues) {
    this.history = history;
    this.deviceLink = deviceLink;
    this.liveValues = liveValues;
  }

  /** Right side of the window: the inspector next to the panel tab. */
  TabPane buildRightTabs(ScrollPane properties) {
    host =
        new PanelBridge(
            this::snapshot,
            new PanelDocumentStore(
                Paths.get(System.getProperty("studio.data"), "panels", "main.properties")),
            ex ->
                SessionLog.append(
                    Paths.get(System.getProperty("studio.data")), "Panel: " + ex.getMessage()));
    module =
        new PanelModules(
            Paths.get(
                System.getProperty(
                    "studio.panelModules", PanelModules.defaultDirectory().toString())),
            host);
    TabPane rightTabs =
        new TabPane(
            new Tab(Tr.of("panel.properties"), properties),
            new Tab(Tr.of("panel.tab"), module.content()));
    rightTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    rightTabs.setMinWidth(240);
    rightTabs.setId("right-tabs");
    return rightTabs;
  }

  /** Push a fresh snapshot to the loaded modules; safe before the tab exists. */
  void publish() {
    if (host != null) host.publish();
  }

  /** Let watched panel signals take a few slots of the current sampling round. */
  void addWatchedTo(Collection<String> ids) {
    if (host == null) return;
    List<String> watched = snapshots.ids(history.get(), host.watched());
    for (int i = 0; i < Math.min(4, watched.size()) && ids.size() < 16; i++)
      ids.add(watched.get(Math.floorMod(pollingCursor + i, watched.size())));
    pollingCursor += 4;
  }

  void close() {
    if (module != null) module.close();
    if (host != null) host.close();
  }

  private PanelData.Snapshot snapshot() {
    return snapshots.create(
        history.get(),
        SessionStore.active(),
        deviceLink.session.connected(),
        host == null ? Set.of() : host.watched(),
        (node, port) -> {
          boolean local =
              deviceLink.baseline == null
                  || !deviceLink.baseline.nodes.containsKey(node.id)
                  || liveValues.pendingValue(node, port);
          long age = local ? 0 : liveValues.ageMs(node);
          var quality =
              local
                  ? ru.bccontrol.panel.api.PanelData.Quality.LOCAL
                  : !deviceLink.session.connected()
                      ? ru.bccontrol.panel.api.PanelData.Quality.OFFLINE
                      : liveValues.readFailed(node)
                          ? ru.bccontrol.panel.api.PanelData.Quality.ERROR
                          : liveValues.stale(age)
                              ? ru.bccontrol.panel.api.PanelData.Quality.STALE
                              : ru.bccontrol.panel.api.PanelData.Quality.GOOD;
          return new ru.bccontrol.panel.api.PanelData.Sample(
              liveValues.fullValue(node, port),
              quality,
              local ? 0 : System.currentTimeMillis() - age,
              liveValues.quality(node, port));
        });
  }
}

package ru.bccontrol.sedonastudio.ui;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
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

/** Online values: sampling clock, value quality, watches, trend traces and CSV export. */
final class LiveValues {
  private final EditorFocus focus;
  private final DeviceLink deviceLink;
  private final Notices notices;
  private final EditorShell shell;
  /** Called about once a second from the sampling clock, on the JavaFX thread. */
  Runnable onHousekeeping = () -> {};
  /** Called after cached values changed; listeners redraw what shows them. */
  Runnable onValuesChanged = () -> {};
  /** Called with every successfully read batch: block id -> slot -> value. */
  java.util.function.Consumer<Map<String, Map<String, String>>> onSample = values -> {};
  /** Lets other parts add block ids to the current sampling round. */
  java.util.function.Consumer<Set<String>> extraWatch = ids -> {};

  final Map<String, Map<String, String>> current = new HashMap<>();
  final Map<String, Long> sampledAt = new HashMap<>();
  private long readAt;
  private final Map<String, Label> valueLabels = new HashMap<>();
  private boolean disconnectedReported;
  CheckBox polling;
  private int pollingCursor;
  private Label liveState;
  long nextSample;
  private final Map<String, Long> retryReadsAt = new HashMap<>();
  private final Map<String, String> readErrors = new LinkedHashMap<>();
  int liveIntervalMs = 1000;
  final LinkedHashMap<String, XYChart.Series<Number, Number>> traces = new LinkedHashMap<>();
  private LineChart<Number, Number> chart;

  private final AtomicBoolean sampling = new AtomicBoolean();
  private long sampleStarted;
  private ScheduledFuture<?> clockTask;
  private long clockGeneration, nextHousekeeping;
  private long clock = System.nanoTime();
  private final long startedWall = System.currentTimeMillis();
  private NumberAxis timeAxis;
  private int trendWindow = 120;

  LiveValues(EditorFocus focus, DeviceLink deviceLink, Notices notices, EditorShell shell) {
    this.focus = focus;
    this.deviceLink = deviceLink;
    this.notices = notices;
    this.shell = shell;
  }

  /** Creates the polling switch and the clickable live-state label with its menu. */
  Label buildControls(Runnable openSettings) {
    polling = new CheckBox(Tr.of("live.onlineValues"));
    polling.setTooltip(new Tooltip(Tr.of("live.onlineValuesTip")));
    liveState = new Label(Tr.of("live.offline"));
    liveState.setStyle("-fx-cursor: hand;");
    liveState.setTooltip(new Tooltip(Tr.of("live.stateTip")));
    ContextMenu pollingMenu = new ContextMenu();
    MenuItem resume = new MenuItem(Tr.of("live.refreshNow")),
        pause = new MenuItem(Tr.of("live.pause")),
        settingsItem = new MenuItem(Tr.of("live.refreshSettings"));
    resume.setOnAction(
        e -> {
          if (!deviceLink.session.connected()) {
            notices.message(Tr.of("live.connectFirst"));
            return;
          }
          retryReadsAt.clear();
          nextSample = 0;
          polling.setSelected(true);
          liveState.setText(Tr.of("live.reading"));
          sample();
          scheduleSampleClock(1);
        });
    pause.setOnAction(e -> polling.setSelected(false));
    settingsItem.setOnAction(e -> notices.guard(openSettings));
    pollingMenu.getItems().addAll(resume, pause, settingsItem);
    liveState.setOnMouseClicked(
        e -> {
          resume.setDisable(!deviceLink.session.connected());
          pollingMenu.show(liveState, javafx.geometry.Side.BOTTOM, 0, 0);
        });
    polling
        .selectedProperty()
        .addListener(
            (o, a, b) -> {
              if (!b)
                liveState.setText(
                    deviceLink.session.connected() ? Tr.of("live.paused") : Tr.of("live.offline"));
            });
    return liveState;
  }

  /** Puts the time axis at "the last trendWindow seconds, ending now". */
  private void moveTrendWindow() {
    if (timeAxis == null) return;
    double now = (System.nanoTime() - clock) / 1e9;
    timeAxis.setUpperBound(now);
    timeAxis.setLowerBound(now - trendWindow);
    timeAxis.setTickUnit(trendWindow / 6.0);
  }

  /** Creates the trend chart with its context menu. */
  LineChart<Number, Number> buildTrendChart() {
    // The time axis is a window that ends now and moves on with every sample.
    NumberAxis x = new NumberAxis(), y = new NumberAxis();
    timeAxis = x;
    x.setAutoRanging(false);
    x.setForceZeroInRange(false);
    x.setMinorTickVisible(false);
    x.setLabel(Tr.of("live.axisTime"));
    java.time.format.DateTimeFormatter clockFormat =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");
    x.setTickLabelFormatter(
        new javafx.util.StringConverter<Number>() {
          public String toString(Number seconds) {
            return java.time.LocalTime.ofInstant(
                    java.time.Instant.ofEpochMilli(
                        startedWall + Math.round(seconds.doubleValue() * 1000)),
                    java.time.ZoneId.systemDefault())
                .format(clockFormat);
          }

          public Number fromString(String text) {
            return 0;
          }
        });
    trendWindow = Math.max(10, shell.prefs().getInt("trendWindowSec", 120));
    moveTrendWindow();
    chart = new LineChart<>(x, y);
    chart.setAnimated(false);
    chart.setCreateSymbols(false);
    chart.setVerticalZeroLineVisible(false);
    chart.setTitle(Tr.of("live.chartTitle"));
    ContextMenu trendMenu = new ContextMenu();
    MenuItem removeSignal = new MenuItem(Tr.of("live.removeSignal")),
        clearSignals = new MenuItem(Tr.of("live.clearTrends"));
    removeSignal.setOnAction(
        e -> {
          if (traces.isEmpty()) return;
          ChoiceDialog<String> choose =
              new ChoiceDialog<>(traces.keySet().iterator().next(), traces.keySet());
          choose.setHeaderText(Tr.of("live.chooseSignal"));
          choose
              .showAndWait()
              .ifPresent(
                  key -> {
                    chart.getData().remove(traces.remove(key));
                  });
        });
    clearSignals.setOnAction(
        e -> {
          traces.clear();
          chart.getData().clear();
        });
    Menu window = new Menu(Tr.of("live.window"));
    for (int seconds : new int[] {60, 120, 300, 900, 3600}) {
      MenuItem item =
          new MenuItem(
              seconds < 3600 ? Tr.of("live.minutes", seconds / 60) : Tr.of("live.hours", 1));
      item.setOnAction(
          e -> {
            trendWindow = seconds;
            shell.prefs().putInt("trendWindowSec", seconds);
            moveTrendWindow();
          });
      window.getItems().add(item);
    }
    trendMenu.getItems().addAll(window, new SeparatorMenuItem(), removeSignal, clearSignals);
    chart.setOnContextMenuRequested(e -> trendMenu.show(chart, e.getScreenX(), e.getScreenY()));
    return chart;
  }

  /** Start over after a fresh device snapshot: forget cached values, errors and trends. */
  void restart() {
    disconnectedReported = false;
    pollingCursor = 0;
    polling.setSelected(
        deviceLink.session.connected() && shell.prefs().getBoolean("liveEnabled", true));
    nextSample = 0;
    liveState.setText(
        deviceLink.session.connected()
            ? (polling.isSelected() ? Tr.of("live.waiting") : Tr.of("live.paused"))
            : Tr.of("live.offline"));
    current.clear();
    sampledAt.clear();
    readErrors.clear();
    retryReadsAt.clear();
    readAt = System.nanoTime();
    traces.clear();
    chart.getData().clear();
    scheduleSampleClock(1);
  }

  /** After changes were applied in the background: go on polling, keep values and trends. */
  void resume() {
    polling.setSelected(
        deviceLink.session.connected() && shell.prefs().getBoolean("liveEnabled", true));
    nextSample = 0;
    scheduleSampleClock(1);
  }

  void pause() {
    polling.setSelected(false);
  }

  void showOffline() {
    liveState.setText(Tr.of("live.offline"));
  }

  ValueState state(Project.Node n, String port) {
    Project.Node old = deviceLink.baseline == null ? null : deviceLink.baseline.nodes.get(n.id);
    if (old == null) return ValueState.LOCAL;
    if (pendingValue(n, port)) return ValueState.PENDING;
    if (!deviceLink.session.connected()) return ValueState.OFFLINE;
    if (readErrors.containsKey(n.id)) return ValueState.ERROR;
    return stale(ageMs(n)) ? ValueState.STALE : ValueState.FRESH;
  }

  /** The state of a value as a sentence for tooltips. */
  String quality(Project.Node n, String port) {
    ValueState state = state(n, port);
    switch (state) {
      case LOCAL:
        return Tr.of("live.q.local");
      case PENDING:
        return Tr.of("live.q.pending");
      case OFFLINE:
        return Tr.of("live.q.offline");
      case ERROR:
        return Tr.of("live.q.readError", readErrors.get(n.id));
      default:
        long age = ageMs(n);
        return Tr.of(
            "live.q.age",
            state == ValueState.STALE ? Tr.of("live.stale") : Tr.of("live.fresh"),
            age < 1000 ? Tr.of("live.ms", age) : Tr.of("live.seconds", age / 1000));
    }
  }

  /** Milliseconds since the block's values were last read from the device. */
  long ageMs(Project.Node n) {
    return Math.max(0, (System.nanoTime() - sampledAt.getOrDefault(n.id, readAt)) / 1_000_000L);
  }

  /** Values of that age are no longer being refreshed: polling is paused or lags behind. */
  boolean stale(long ageMs) {
    return !polling.isSelected() || ageMs > Math.max(1000L, liveIntervalMs * 3L);
  }

  boolean readFailed(Project.Node n) {
    return readErrors.containsKey(n.id);
  }

  int intervalMs() {
    return liveIntervalMs;
  }

  /** Polling interval at startup, before anything is connected. */
  void restoreInterval(int intervalMs) {
    liveIntervalMs = intervalMs;
  }

  /** Apply the polling settings chosen by the user and sample again right away. */
  void configure(int intervalMs, boolean enabled) {
    liveIntervalMs = intervalMs;
    nextSample = 0;
    polling.setSelected(deviceLink.session.connected() && enabled);
    scheduleSampleClock(1);
  }

  /** The inspector's labels that show the selected block's live values. */
  void clearValueLabels() {
    valueLabels.clear();
  }

  void showValueIn(String port, Label label) {
    valueLabels.put(port, label);
  }

  boolean pendingValue(Project.Node n, String port) {
    Project.Node old = deviceLink.baseline == null ? null : deviceLink.baseline.nodes.get(n.id);
    Project.Port slot = n.port(port);
    return old != null
        && slot != null
        && !slot.readonly
        && !Objects.equals(n.values.get(port), old.values.get(port));
  }

  String fullValue(Project.Node n, String port) {
    Project.Node old = deviceLink.baseline == null ? null : deviceLink.baseline.nodes.get(n.id);
    String value = n.values.getOrDefault(port, "—");
    if (old != null && !pendingValue(n, port))
      value = current.getOrDefault(n.id, n.values).getOrDefault(port, value);
    return value;
  }

  String display(Project.Node n, String port) {
    String value = fullValue(n, port);
    return value.length() > 16 ? value.substring(0, 13) + "…" : value;
  }

  void watch(Project.Node n, Project.Port port) {
    String key = n.id + "." + port.name;
    if (traces.containsKey(key)) return;
    if (traces.size() >= 8) throw new IllegalArgumentException(Tr.of("live.maxSignals"));
    if (!port.type.matches("sys::(bool|byte|short|int|long|float|double)"))
      throw new IllegalArgumentException(Tr.of("live.numericOnly"));
    XYChart.Series<Number, Number> series = new XYChart.Series<>();
    series.setName(n.name + "." + port.name);
    traces.put(key, series);
    chart.getData().add(series);
    notices.message(Tr.of("live.signalAdded"));
  }

  void scheduleSampleClock(long delayMs) {
    if (deviceLink.timer == null || deviceLink.timer.isShutdown() || deviceLink.io.isShutdown())
      return;
    if (clockTask != null) clockTask.cancel(false);
    long generation = ++clockGeneration;
    clockTask =
        deviceLink.timer.schedule(
            () ->
                Platform.runLater(
                    () -> {
                      if (generation == clockGeneration) sampleClockPulse();
                    }),
            Math.max(1, delayMs),
            TimeUnit.MILLISECONDS);
  }

  private void sampleClockPulse() {
    if (deviceLink.io.isShutdown()) return;
    try {
      sample();
    } catch (RuntimeException e) {
      notices.message(Tr.of("live.cycleError", e.getMessage()));
    } finally {
      if (!deviceLink.io.isShutdown()) {
        long delay = 1000;
        if (polling.isSelected() && deviceLink.session.connected() && deviceLink.baseline != null) {
          delay =
              deviceLink.busy.get()
                  ? 100
                  : sampling.get()
                      ? 50
                      : Math.max(
                          10,
                          Math.min(1000, (nextSample - System.nanoTime() + 999999) / 1_000_000));
        }
        scheduleSampleClock(delay);
      }
    }
  }

  void sample() {
    if (deviceLink.io.isShutdown()) return;
    if (System.nanoTime() >= nextHousekeeping) {
      nextHousekeeping = System.nanoTime() + 1_000_000_000L;
      onHousekeeping.run();
      if (deviceLink.io.isShutdown()) return;
      onValuesChanged.run();
    }
    if (deviceLink.baseline != null && !deviceLink.session.connected()) {
      if (!disconnectedReported) {
        disconnectedReported = true;
        polling.setSelected(false);
        notices.message(Tr.of("live.offlineCached"));
      }
      return;
    }

    if (sampling.get() && System.nanoTime() - sampleStarted > 3_000_000_000L)
      liveState.setText(
          Tr.of("live.readingFor", ((System.nanoTime() - sampleStarted) / 1_000_000_000L)));
    if (deviceLink.session.connected() && deviceLink.baseline == null) {
      liveState.setText(Tr.of("live.readDevice"));
      return;
    }
    if (!polling.isSelected()
        || !deviceLink.session.connected()
        || deviceLink.busy.get()
        || System.nanoTime() < nextSample) return;
    Set<String> ids = new LinkedHashSet<>();
    for (String key : traces.keySet()) ids.add(key.substring(0, key.indexOf('.')));
    if (focus.selected != null && shell.stage().isShowing()) ids.add(focus.selected);
    if (focus.selectedWire != null && shell.stage().isShowing()) {
      ids.add(focus.selectedWire.source);
      ids.add(focus.selectedWire.target);
    }
    if (shell.stage().isShowing()) extraWatch.accept(ids);
    Node viewport = shell.diagramScroll().lookup(".viewport");
    Bounds bounds =
        viewport == null
            ? shell.diagramScroll().localToScene(shell.diagramScroll().getLayoutBounds())
            : viewport.localToScene(viewport.getLayoutBounds());
    List<String> visible = shell.stage().isShowing() ? shell.world().visibleIds(bounds) : List.of();
    for (int i = 0; i < visible.size() && ids.size() < 16; i++)
      ids.add(visible.get(Math.floorMod(pollingCursor + i, visible.size())));
    pollingCursor += Math.max(1, 16 - Math.min(9, traces.size() + 1));
    ids.removeIf(id -> !deviceLink.baseline.nodes.containsKey(id));
    if (ids.isEmpty()) {
      liveState.setText(Tr.of("live.noVisible"));
      nextSample = System.nanoTime() + 1_000_000_000L;
      return;
    }
    ids.removeIf(id -> System.nanoTime() < retryReadsAt.getOrDefault(id, 0L));
    if (ids.isEmpty()) {
      liveState.setText(Tr.of("live.retrying"));
      nextSample = System.nanoTime() + 1_000_000_000L;
      return;
    }
    if (!sampling.compareAndSet(false, true)) return;
    sampleStarted = System.nanoTime();
    nextSample =
        System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(liveIntervalMs);
    Project expected = deviceLink.baseline;
    deviceLink.io.submit(
        () -> {
          SampleBatch.Result batch =
              SampleBatch.read(ids, deviceLink.session::sample, deviceLink.session::connected);
          Map<String, Map<String, String>> values = batch.values();
          Platform.runLater(
              () -> {
                sampling.set(false);
                if (deviceLink.baseline != expected || !deviceLink.session.connected()) return;
                for (var failure : batch.errors().entrySet()) {
                  readErrors.put(failure.getKey(), failure.getValue());
                  retryReadsAt.put(failure.getKey(), System.nanoTime() + 10_000_000_000L);
                  notices.message(
                      Tr.of("live.blockReadError", failure.getKey(), failure.getValue()));
                }
                for (String id : values.keySet()) {
                  readErrors.remove(id);
                  retryReadsAt.remove(id);
                }
                current.putAll(values);
                sampledAt.putAll(batch.times());
                onValuesChanged.run();
                onSample.accept(values);
                moveTrendWindow();
                for (Map.Entry<String, XYChart.Series<Number, Number>> entry : traces.entrySet()) {
                  String[] key = entry.getKey().split("[.]", 2);
                  String value = values.getOrDefault(key[0], Collections.emptyMap()).get(key[1]);
                  try {
                    double number =
                        "true".equals(value)
                            ? 1
                            : "false".equals(value) ? 0 : Double.parseDouble(value);
                    if (!Double.isFinite(number)) continue;
                    entry
                        .getValue()
                        .getData()
                        .add(new XYChart.Data<>((System.nanoTime() - clock) / 1e9, number));
                    // Keep what the widest window (an hour) can show, drop the rest.
                    while (entry.getValue().getData().size() > 1
                        && (entry.getValue().getData().size() > 20000
                            || entry.getValue().getData().get(0).getXValue().doubleValue()
                                < (System.nanoTime() - clock) / 1e9 - 3600))
                      entry.getValue().getData().remove(0);
                  } catch (Exception ignored) {
                  }
                }
                if (focus.selected != null && values.containsKey(focus.selected))
                  for (Map.Entry<String, Label> e : valueLabels.entrySet())
                    e.getValue()
                        .setText(
                            Tr.of(
                                "live.cache",
                                values
                                    .getOrDefault(focus.selected, Collections.emptyMap())
                                    .getOrDefault(e.getKey(), "—")));
                if (values.isEmpty()) liveState.setText(Tr.of("live.readErrorRetry"));
                else
                  liveState.setText(
                      Tr.of(
                          "live.online",
                          java.time.LocalTime.now().withNano(0),
                          values.size(),
                          (readErrors.isEmpty()
                              ? ""
                              : Tr.of("live.errorCount", readErrors.size()))));
              });
        });
  }

  void exportCsv() {
    if (traces.isEmpty()) throw new IllegalStateException(Tr.of("live.addSignals"));
    FileChooser chooser = new FileChooser();
    chooser.setInitialFileName("sedona-trend.csv");
    File file = chooser.showSaveDialog(shell.stage());
    if (file == null) return;
    StringBuilder csv = new StringBuilder("\uFEFFsignal,ui_elapsed_seconds,value\r\n");
    for (Map.Entry<String, XYChart.Series<Number, Number>> entry : traces.entrySet())
      for (XYChart.Data<Number, Number> value : entry.getValue().getData())
        csv.append('"')
            .append(entry.getValue().getName().replace("\"", "\"\""))
            .append("\",")
            .append(value.getXValue())
            .append(',')
            .append(value.getYValue())
            .append("\r\n");
    try {
      Files.write(file.toPath(), csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      notices.message(Tr.of("live.csvSaved", file.getName()));
    } catch (IOException e) {
      notices.error(e);
    }
  }
}

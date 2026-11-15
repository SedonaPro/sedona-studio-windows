import static ru.bccontrol.panel.api.PanelData.*;

import java.util.*;
import ru.bccontrol.panel.api.*;
import ru.bccontrol.panel.demo.*;
import ru.bccontrol.panel.widgets.*;

public class PanelTests {
  static void check(boolean b, String text) {
    if (!b) throw new AssertionError(text);
  }

  public static void main(String[] args) throws Exception {
    var widgets =
        List.of(
            new PanelDocument.Widget("value", "Temperature", new Ref("/plant/pump", "value")),
            new PanelDocument.Widget("indicator", "Enabled", new Ref("/plant/pump", "enabled")));
    check(PanelDocument.read(PanelDocument.write(widgets)).equals(widgets), "Document roundtrip");
    check(PanelDocument.read("").isEmpty(), "Empty document");
    var scales = List.of(
        new PanelDocument.Widget("gauge", "Pressure", new Ref("/plant", "p"), -2, 12, "bar"),
        new PanelDocument.Widget("thermometer", "Temperature", new Ref("/plant", "t"), -40, 120, "°C"),
        new PanelDocument.Widget("bar", "Valve", new Ref("/plant", "v"), 0, 100, "%"));
    check(PanelDocument.read(PanelDocument.write(scales)).equals(scales), "Scale range/unit roundtrip");
    for (String type : List.of("tank", "damper", "fan", "pump", "bipolar")) {
      var scenario = List.of(new PanelDocument.Widget(type, type, new Ref("/plant", "signal"), -100, 100, "%"));
      check(PanelDocument.read(PanelDocument.write(scenario)).equals(scenario), "Scenario persisted: " + type);
    }
    for (String limits : List.of("min=2\nwidget.0.max=1", "min=NaN\nwidget.0.max=100")) {
      boolean bad = false;
      try { PanelDocument.read("version=1\ncount=1\nwidget.0.kind=gauge\nwidget.0.path=/plant\nwidget.0.field=p\nwidget.0." + limits); }
      catch (Exception ex) { bad = true; }
      check(bad, "Invalid scale limits rejected");
    }
    boolean rejected = false;
    try {
      PanelDocument.read("version=99\ncount=0");
    } catch (Exception ex) {
      rejected = true;
    }
    check(rejected, "Unknown version rejected");
    rejected = false;
    try {
      PanelDocument.read("x".repeat(262145));
    } catch (Exception ex) {
      rejected = true;
    }
    check(rejected, "Document bound");
    DemoHost host = new DemoHost();
    int[] notifications = {0};
    var sub = host.subscribe(s -> notifications[0]++);
    host.tick();
    check(notifications[0] == 2, "Mock subscription");
    sub.close();
    host.tick();
    check(notifications[0] == 2, "Unsubscribe");
    check(
        host.write(new Ref("/demo", "enabled"), "false").toCompletableFuture().get().outcome()
            == Outcome.CONFIRMED,
        "Mock write");
    check(
        host.write(new Ref("/demo", "enabled"), "wrong").toCompletableFuture().get().outcome()
            == Outcome.REJECTED,
        "Mock validation");
    check(
        host.invoke(new Ref("/demo", "reset"), "").toCompletableFuture().get().outcome()
            == Outcome.CONFIRMED,
        "Mock action");
    check(
        host.snapshot().values().get(new Ref("/demo", "value")).value().equals("0.00"),
        "Mock reset readback");
    boolean immutable = false;
    try {
      host.snapshot().components().clear();
    } catch (UnsupportedOperationException ex) {
      immutable = true;
    }
    check(immutable, "Immutable API");
    System.out.println(
        "PASS independent panel contracts: document versions/bounds/roundtrip, immutable DTOs, mock"
            + " writes/actions, subscriptions and unsubscribe");
  }
}

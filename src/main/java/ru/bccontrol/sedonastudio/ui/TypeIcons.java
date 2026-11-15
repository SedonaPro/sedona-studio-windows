package ru.bccontrol.sedonastudio.ui;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.*;
import javax.imageio.ImageIO;
import ru.bccontrol.sedonastudio.model.*;
import ru.bccontrol.sedonastudio.storage.IconPack;

/** One preloaded image cache; each cell receives its own node. Rendering never reads disk. */
final class TypeIcons {
  private static volatile Map<String, Image> images = Map.of();
  private static final Map<String, String> metadata = new HashMap<>();
  private static final ExecutorService loader =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "sedona-icon-images");
            t.setDaemon(true);
            return t;
          });
  private static long generation;
  static boolean enabled = true, showNotes = true;
  static String source = "";
  static String failure = "";
  static java.util.function.Function<String, String> description = type -> "";

  static void register(Collection<Project.Node> nodes) {
    for (Project.Node n : nodes)
      if (!n.typeIcon.isBlank()) metadata.put(n.kitPart + "|" + n.type, n.typeIcon);
  }

  static void configure(String path, boolean showIcons, boolean notes, Runnable refresh) {
    enabled = showIcons;
    showNotes = notes;
    if (Objects.equals(source, path)) {
      refresh.run();
      return;
    }
    source = path;
    images = Map.of();
    failure = "";
    final long requested = ++generation;
    if (path.isBlank()) {
      refresh.run();
      return;
    }
    loader.submit(
        () -> {
          Map<String, Image> loaded = new HashMap<>();
          String error = "";
          try {
            for (var item : IconPack.read(Path.of(path)).entrySet()) {
              byte[] bytes = item.getValue();
              // Reject oversized raster headers before decoding; no animated formats are accepted.
              try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) continue;
                var reader = readers.next();
                try {
                  reader.setInput(input);
                  if (reader.getWidth(0) > 128 || reader.getHeight(0) > 128) continue;
                } finally {
                  reader.dispose();
                }
              }
              Image image = new Image(new ByteArrayInputStream(bytes), 32, 32, true, false);
              if (!image.isError()) loaded.put(item.getKey(), image);
            }
            if (loaded.isEmpty()) error = Tr.of("annotation.noImages");
          } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
          }
          final String message = error;
          Platform.runLater(
              () -> {
                if (generation != requested) return;
                images = Map.copyOf(loaded);
                failure = message;
                refresh.run();
              });
        });
  }

  static int count() {
    return images.size();
  }

  static boolean hasOriginal(Project.Node n) {
    return images.containsKey(IconPack.key(uri(n)));
  }

  static String uri(Project.Node n) {
    return n.typeIcon.isBlank() ? metadata.getOrDefault(n.kitPart + "|" + n.type, "") : n.typeIcon;
  }

  static Node graphic(Project.Node n, double size) {
    if (!enabled) return null;
    Image original = images.get(IconPack.key(uri(n)));
    if (original != null) {
      ImageView view = new ImageView(original);
      view.setFitWidth(size);
      view.setFitHeight(size);
      view.setPreserveRatio(true);
      view.setSmooth(false);
      view.setMouseTransparent(true);
      return view;
    }
    String type = n.type == null ? "" : n.type.toLowerCase(Locale.ROOT);
    String symbol = "◇", color = "#63788c";
    if (type.contains("folder")) {
      symbol = "▰";
      color = "#b78731";
    } else if (type.contains("math") || type.contains("expression")) {
      symbol = "∑";
      color = "#c47e27";
    } else if (type.contains("logic") || type.contains("bool")) {
      symbol = "&";
      color = "#258c79";
    } else if (type.contains("tim") || type.contains("schedule") || type.contains("ramp")) {
      symbol = "◷";
      color = "#4b87ac";
    } else if (type.contains("pid") || type.contains("control") || type.contains("hvac")) {
      symbol = "↗";
      color = "#7962aa";
    } else if (type.contains("inout") || type.contains("driver") || type.contains("serial")) {
      symbol = "⇄";
      color = "#527f8b";
    }
    Label label = new Label(symbol);
    label.setFont(Font.font("Segoe UI", FontWeight.BOLD, size * .8));
    label.setTextFill(Color.web(color));
    label.setMinSize(size, size);
    label.setPrefSize(size, size);
    label.setMaxSize(size, size);
    label.setAlignment(javafx.geometry.Pos.CENTER);
    label.setMouseTransparent(true);
    return label;
  }

  static String info(Project.Node n, String help) {
    if (!n.info || !showNotes) return "";
    return switch (n.infoSource) {
      case "comment" -> n.note;
      case "type" -> help == null ? "" : help;
      default -> n.note.isBlank() ? (help == null ? "" : help) : n.note;
    };
  }

  static String tooltip(Project.Node n, String help) {
    String text = n.type + (n.kitPart == null ? "" : "\n" + n.kitPart);
    if (help != null && !help.isBlank())
      text += "\n\n" + Tr.of("annotation.typeDescription") + "\n" + help;
    if (!n.note.isBlank()) text += "\n\n" + Tr.of("annotation.instanceComment") + "\n" + n.note;
    return text;
  }
}

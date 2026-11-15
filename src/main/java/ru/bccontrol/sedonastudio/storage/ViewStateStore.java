package ru.bccontrol.sedonastudio.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.Tr;

/** Per-session view settings. Kept separate from the application graph and device writes. */
public final class ViewStateStore {
  public record State(
      String project,
      String folder,
      String lineage,
      double zoom,
      double h,
      double v,
      double left,
      double right,
      double bottom,
      int leftTab,
      int bottomTab) {}

  public static String projectKey(Project p) {
    return Json.write(
        ProjectStore.map(
            "name", p.name, "platform", p.platform, "parts", p.parts.stream().sorted().toList()));
  }

  public static String lineage(Project p, String id) {
    List<String> path = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    while (!id.equals("0")) {
      Project.Node n = p.nodes.get(id);
      if (n == null || !seen.add(id)) return "";
      path.add(n.name + "|" + n.type);
      id = n.parent;
    }
    Collections.reverse(path);
    return Json.write(path);
  }

  public static String restoreFolder(State s, Project p) {
    if (!s.project.equals(projectKey(p))) return "0";
    return s.folder.equals("0")
            || p.nodes.containsKey(s.folder) && lineage(p, s.folder).equals(s.lineage)
        ? s.folder
        : "0";
  }

  private static double number(Map<String, Object> m, String key, double min, double max) {
    double v = ((Number) m.get(key)).doubleValue();
    if (!Double.isFinite(v) || v < min || v > max)
      throw new IllegalArgumentException("View field: " + key);
    return v;
  }

  private static State decode(String json) {
    Map<String, Object> m = Json.object(json);
    if (((Number) m.get("version")).intValue() != 1)
      throw new IllegalArgumentException("View version");
    State s =
        new State(
            (String) m.get("project"),
            (String) m.get("folder"),
            (String) m.get("lineage"),
            number(m, "zoom", .2, 3),
            number(m, "h", 0, 1),
            number(m, "v", 0, 1),
            number(m, "left", 0, 1),
            number(m, "right", 0, 1),
            number(m, "bottom", 0, 1),
            (int) number(m, "leftTab", 0, 2),
            (int) number(m, "bottomTab", 0, 2));
    if (s.project == null || s.folder == null || s.lineage == null || s.left >= s.right)
      throw new IllegalArgumentException("View state");
    return s;
  }

  public static Optional<State> load(Path file) throws IOException {
    if (!Files.exists(file)) return Optional.empty();
    try {
      if (Files.size(file) > 32768) throw new IllegalArgumentException("View too large");
      return Optional.of(decode(Files.readString(file)));
    } catch (RuntimeException e) {
      throw new IOException(Tr.of("view.damaged"), e);
    }
  }

  public static void save(State s, Path file) throws IOException {
    String json =
        Json.write(
            ProjectStore.map(
                "version",
                1,
                "project",
                s.project,
                "folder",
                s.folder,
                "lineage",
                s.lineage,
                "zoom",
                s.zoom,
                "h",
                s.h,
                "v",
                s.v,
                "left",
                s.left,
                "right",
                s.right,
                "bottom",
                s.bottom,
                "leftTab",
                s.leftTab,
                "bottomTab",
                s.bottomTab));
    decode(json);
    Files.createDirectories(file.toAbsolutePath().getParent());
    Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), "view-", ".tmp");
    try {
      Files.writeString(tmp, json);
      try {
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}

package ru.bccontrol.sedonastudio.storage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.Tr;

/** Versioned explicit format, strict limits, atomic replacement and a previous-file backup. */
public final class ProjectStore {
  public static Map<String, Object> map(Object... v) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < v.length; i += 2) m.put((String) v[i], v[i + 1]);
    return m;
  }

  public static String encode(Project p) {
    List<Object> nodes = new ArrayList<>(), wires = new ArrayList<>();
    for (Project.Node n : p.nodes.values()) {
      List<Object> ports = new ArrayList<>();
      for (Project.Port t : n.ports)
        ports.add(
            map(
                "id",
                t.id,
                "name",
                t.name,
                "type",
                t.type,
                "input",
                t.input,
                "readonly",
                t.readonly,
                "asStr",
                t.asStr,
                "textMax",
                t.textMax,
                "config",
                t.config));
      Map<String, String> publicValues = new LinkedHashMap<>();
      for (Map.Entry<String, String> value : n.values.entrySet())
        if (!ru.bccontrol.sedonastudio.model.SecretFields.matches(value.getKey()))
          publicValues.put(value.getKey(), value.getValue());
      nodes.add(
          map(
              "id",
              n.id,
              "name",
              n.name,
              "type",
              n.type,
              "kitPart",
              n.kitPart,
              "note",
              n.note,
              "width",
              n.width,
              "icon",
              n.icon,
              "info",
              n.info,
              "infoSource",
              n.infoSource,
              "typeIcon",
              n.typeIcon,
              "parent",
              n.parent,
              "x",
              n.x,
              "y",
              n.y,
              "ports",
              ports,
              "values",
              publicValues));
    }
    for (Project.Wire w : p.wires)
      wires.add(
          map(
              "source",
              w.source,
              "sourcePort",
              w.sourcePort,
              "target",
              w.target,
              "targetPort",
              w.targetPort,
              "bends",
              w.bends.stream().map(point -> List.of(point.x(), point.y())).toList()));
    List<Object> captions = new ArrayList<>();
    for (Project.Caption c : p.captions)
      captions.add(
          map(
              "id", c.id, "folder", c.folder, "text", c.text, "icon", c.icon, "anchor", c.anchor,
              "color", c.color, "x", c.x, "y", c.y, "width", c.width, "height", c.height, "frame",
              c.frame));
    return Json.write(
        map(
            "format",
            "sedona-studio",
            "version",
            1,
            "name",
            p.name,
            "platform",
            p.platform,
            "parts",
            p.parts,
            "nodes",
            nodes,
            "wires",
            wires,
            "captions",
            captions));
  }

  private static String text(Map<?, ?> m, String key) {
    Object v = m.get(key);
    if (!(v instanceof String) || ((String) v).length() > 4096)
      throw new IllegalArgumentException(Tr.of("store.string", key));
    return (String) v;
  }

  private static List<?> list(Map<?, ?> m, String key) {
    Object v = m.get(key);
    if (!(v instanceof List) || ((List<?>) v).size() > 8192)
      throw new IllegalArgumentException(Tr.of("store.list", key));
    return (List<?>) v;
  }

  public static Project decode(String json) {
    if (json.length() > 4 * 1024 * 1024)
      throw new IllegalArgumentException(Tr.of("store.tooLarge"));
    Map<String, Object> data = Json.object(json);
    if (!"sedona-studio".equals(data.get("format"))
        || !(data.get("version") instanceof Number)
        || ((Number) data.get("version")).doubleValue() != 1)
      throw new IllegalArgumentException(Tr.of("store.unknownFormat"));
    Project p = new Project();
    p.name = text(data, "name");
    p.platform = text(data, "platform");
    for (Object v : list(data, "parts")) p.parts.add((String) v);
    for (Object o : list(data, "nodes")) {
      Map<?, ?> m = (Map<?, ?>) o;
      Project.Node n = new Project.Node();
      n.id = text(m, "id");
      n.name = text(m, "name");
      n.type = text(m, "type");
      n.kitPart = text(m, "kitPart");
      n.parent = text(m, "parent");
      n.note = m.containsKey("note") ? text(m, "note") : "";
      n.width =
          m.get("width") instanceof Number width
              ? Math.max(0, Math.min(960, width.doubleValue()))
              : 0;
      n.icon = m.get("icon") instanceof String icon && icon.length() <= 4 ? icon : "";
      n.info = Boolean.TRUE.equals(m.get("info"));
      n.infoSource =
          m.get("infoSource") instanceof String v && List.of("auto", "comment", "type").contains(v)
              ? v
              : "auto";
      n.typeIcon = m.get("typeIcon") instanceof String v && v.length() < 512 ? v : "";
      if (n.note.length() > 4000) throw new IllegalArgumentException(Tr.of("store.noteTooLong"));
      n.x = ((Number) m.get("x")).doubleValue();
      n.y = ((Number) m.get("y")).doubleValue();
      Set<String> portNames = new HashSet<>();
      for (Object item : list(m, "ports")) {
        Map<?, ?> v = (Map<?, ?>) item;
        Project.Port t = new Project.Port();
        t.id = ((Number) v.get("id")).intValue();
        t.name = text(v, "name");
        t.type = text(v, "type");
        t.input = Boolean.TRUE.equals(v.get("input"));
        t.config = Boolean.TRUE.equals(v.get("config"));
        t.asStr = Boolean.TRUE.equals(v.get("asStr"));
        t.textMax = v.get("textMax") instanceof Number limit ? limit.intValue() : 0;
        t.readonly = Boolean.TRUE.equals(v.get("readonly"));
        if (!portNames.add(t.name))
          throw new IllegalArgumentException(Tr.of("store.duplicatePort"));
        n.ports.add(t);
      }
      Map<?, ?> values = (Map<?, ?>) m.get("values");
      if (values.size() > 512) throw new IllegalArgumentException(Tr.of("store.tooManyFields"));
      for (Map.Entry<?, ?> e : values.entrySet()) {
        String key = (String) e.getKey();
        if (ru.bccontrol.sedonastudio.model.SecretFields.matches(key))
          throw new IllegalArgumentException(Tr.of("store.noSecrets"));
        if (!(e.getValue() instanceof String) || ((String) e.getValue()).length() > 4096)
          throw new IllegalArgumentException(Tr.of("store.badFieldValue"));
        n.values.put(key, (String) e.getValue());
      }
      if (p.nodes.put(n.id, n) != null)
        throw new IllegalArgumentException(Tr.of("store.duplicateId"));
    }
    for (Object o : list(data, "wires")) {
      Map<?, ?> m = (Map<?, ?>) o;
      Project.Wire wire =
          new Project.Wire(
              text(m, "source"), text(m, "sourcePort"), text(m, "target"), text(m, "targetPort"));
      if (m.containsKey("bends"))
        for (Object item : (List<?>) m.get("bends")) {
          List<?> xy = (List<?>) item;
          if (xy.size() != 2) throw new IllegalArgumentException(Tr.of("store.bendPoint"));
          wire.bends.add(
              new Project.Point(
                  ((Number) xy.get(0)).doubleValue(), ((Number) xy.get(1)).doubleValue()));
        }
      p.wires.add(wire);
    }
    if (data.get("captions") instanceof List<?> saved)
      for (Object o : saved) {
        Map<?, ?> m = (Map<?, ?>) o;
        Project.Caption c = new Project.Caption();
        c.id = text(m, "id");
        c.folder = text(m, "folder");
        c.text = text(m, "text");
        c.icon = m.get("icon") instanceof String icon && icon.length() <= 4 ? icon : "";
        c.anchor = m.get("anchor") instanceof String v && v.length() < 128 ? v : "";
        c.color =
            m.get("color") instanceof String v
                    && List.of("paper", "blue", "green", "amber").contains(v)
                ? v
                : "paper";
        c.x = ((Number) m.get("x")).doubleValue();
        c.y = ((Number) m.get("y")).doubleValue();
        c.width = m.get("width") instanceof Number w ? w.doubleValue() : 240;
        c.height = m.get("height") instanceof Number h ? h.doubleValue() : 120;
        c.frame = Boolean.TRUE.equals(m.get("frame"));
        p.captions.add(c);
      }
    List<String> errors = p.validate();
    if (!errors.isEmpty()) throw new IllegalArgumentException(errors.get(0));
    return p;
  }

  public static Project load(Path file) throws IOException {
    if (Files.size(file) > 4 * 1024 * 1024) throw new IOException(Tr.of("store.tooLarge"));
    return decode(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
  }

  /** Session recovery preserves the unreadable original and never replaces a valid backup. */
  public static Project loadRecovering(Path file) throws IOException {
    try {
      return load(file);
    } catch (IOException | RuntimeException broken) {
      Path backup = file.resolveSibling(file.getFileName() + ".bak");
      Project recovered;
      try {
        recovered = load(backup);
      } catch (IOException | RuntimeException unavailable) {
        for (Path original : List.of(file, backup))
          if (Files.exists(original))
            Files.copy(
                original,
                original.resolveSibling(original.getFileName() + ".damaged-" + UUID.randomUUID()));
        throw new IOException(Tr.of("store.draftUnavailable"), broken);
      }
      if (Files.exists(file))
        Files.copy(file, file.resolveSibling(file.getFileName() + ".damaged-" + UUID.randomUUID()));
      Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "recover-", ".tmp");
      try {
        Files.writeString(temp, encode(recovered), StandardCharsets.UTF_8);
        try {
          Files.move(
              temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(temp);
      }
      return recovered;
    }
  }

  public static void save(Project p, Path file) throws IOException {
    List<String> errors = p.validate();
    if (!errors.isEmpty()) throw new IOException(errors.get(0));
    Path target = file.toAbsolutePath();
    Files.createDirectories(target.getParent());
    Path temp = Files.createTempFile(target.getParent(), "sedona-", ".tmp");
    try {
      Files.write(temp, encode(p).getBytes(StandardCharsets.UTF_8));
      if (Files.exists(target))
        Files.copy(
            target,
            target.resolveSibling(target.getFileName() + ".bak"),
            StandardCopyOption.REPLACE_EXISTING);
      try {
        Files.move(
            temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}

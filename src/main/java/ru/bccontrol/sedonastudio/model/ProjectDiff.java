package ru.bccontrol.sedonastudio.model;

import java.util.*;

/** Detached review of device changes; never reads transport or includes runtime values. */
public final class ProjectDiff {
  private ProjectDiff() {}

  public record Change(String kind, String subject, String before, String after) {}

  public static List<Change> compare(Project before, Project after) {
    List<Change> out = new ArrayList<>();
    Set<String> oldWires = new HashSet<>(), newWires = new HashSet<>();
    before.wires.forEach(w -> oldWires.add(w.key()));
    after.wires.forEach(w -> newWires.add(w.key()));
    for (Project.Wire w : before.wires)
      if (!newWires.contains(w.key()))
        out.add(new Change(Tr.of("diff.removal"), Tr.of("diff.link"), wire(before, w), "—"));
    for (Project.Node n : after.nodes.values()) {
      Project.Node old = before.nodes.get(n.id);
      String name = n.name + " [" + n.id + "]";
      if (old == null)
        out.add(
            new Change(
                Tr.of("diff.addition"), name, "—", Tr.of("diff.inFolder", n.type, n.parent)));
      else {
        change(out, Tr.of("diff.name", name), old.name, n.name);
        change(out, Tr.of("diff.type", name), old.type, n.type);
        change(out, Tr.of("diff.kit", name), old.kitPart, n.kitPart);
        change(out, Tr.of("diff.folder", name), old.parent, n.parent);
        if (old.x != n.x || old.y != n.y)
          change(out, Tr.of("diff.position", name), old.x + ", " + old.y, n.x + ", " + n.y);
      }
      for (Project.Port p : n.ports) {
        if (p.readonly || SecretFields.matches(p.name)) continue;
        String value = n.values.get(p.name);
        if (old == null) {
          if (value != null)
            out.add(new Change(Tr.of("diff.addition"), name + "." + p.name, "—", value));
        } else change(out, name + "." + p.name, old.values.get(p.name), value);
      }
    }
    for (Project.Wire w : after.wires)
      if (!oldWires.contains(w.key()))
        out.add(new Change(Tr.of("diff.addition"), Tr.of("diff.link"), "—", wire(after, w)));
    for (Project.Node n : before.nodes.values())
      if (!after.nodes.containsKey(n.id))
        out.add(new Change(Tr.of("diff.removal"), n.name + " [" + n.id + "]", n.type, "—"));
    return List.copyOf(out);
  }

  private static void change(List<Change> out, String subject, String before, String after) {
    if (!Objects.equals(before, after))
      out.add(
          new Change(
              Tr.of("diff.change"),
              subject,
              Objects.toString(before, "—"),
              Objects.toString(after, "—")));
  }

  private static String wire(Project p, Project.Wire w) {
    return endpoint(p, w.source, w.sourcePort) + " → " + endpoint(p, w.target, w.targetPort);
  }

  private static String endpoint(Project p, String id, String port) {
    Project.Node n = p.nodes.get(id);
    return (n == null ? id : n.name + " [" + id + "]") + "." + port;
  }
}

package ru.bccontrol.sedonastudio.model;

import java.util.*;

/** Atomic graph commands; caller supplies History.edit and owns selection/clipboard state. */
public final class EditorCommands {
  public static Set<String> subtree(Project p, Collection<String> ids) {
    Set<String> result = new LinkedHashSet<>(ids);
    result.remove("0");
    result.removeIf(id -> !p.nodes.containsKey(id));
    boolean changed;
    do {
      changed = false;
      for (Project.Node n : p.nodes.values())
        if (!n.id.equals("0") && result.contains(n.parent)) changed |= result.add(n.id);
    } while (changed);
    return result;
  }

  public static Set<String> roots(Project p, Collection<String> ids) {
    Set<String> all = new LinkedHashSet<>(ids);
    all.remove("0");
    all.removeIf(id -> !p.nodes.containsKey(id));
    all.removeIf(
        id -> {
          String parent = p.nodes.get(id).parent;
          Set<String> seen = new HashSet<>();
          while (!parent.equals("0") && seen.add(parent)) {
            if (ids.contains(parent)) return true;
            Project.Node n = p.nodes.get(parent);
            if (n == null) break;
            parent = n.parent;
          }
          return false;
        });
    return all;
  }

  public static Project fragment(Project p, Collection<String> ids) {
    Set<String> all = subtree(p, ids);
    Project out = new Project();
    out.name = "Clipboard";
    out.parts.addAll(p.parts);
    for (String id : all) {
      Project.Node n = p.nodes.get(id).copy();
      if (!all.contains(n.parent)) n.parent = "0";
      out.nodes.put(id, n);
    }
    for (Project.Wire w : p.wires)
      if (all.contains(w.source) && all.contains(w.target)) out.wires.add(w.copy());
    for (Project.Caption old : p.captions)
      if (all.contains(old.anchor) || all.contains(old.folder)) {
        Project.Caption c = old.copy();
        if (!all.contains(c.folder)) c.folder = "0";
        if (!all.contains(c.anchor)) c.anchor = "";
        out.captions.add(c);
      }
    return out;
  }

  public static Set<String> paste(Project target, Project fragment, String parent, double offset) {
    Map<String, String> ids = pasteMap(target, fragment, parent, offset);
    Set<String> top = new LinkedHashSet<>();
    for (Project.Node n : fragment.nodes.values())
      if (!fragment.nodes.containsKey(n.parent)) top.add(ids.get(n.id));
    return top;
  }

  public static Map<String, String> pasteMap(
      Project target, Project fragment, String parent, double offset) {
    if (!parent.equals("0") && !target.nodes.containsKey(parent))
      throw new IllegalArgumentException(Tr.of("edit.noTargetFolder"));
    for (String part : fragment.parts) {
      String name = part.substring(0, part.lastIndexOf('-'));
      for (String old : target.parts)
        if (old.substring(0, old.lastIndexOf('-')).equals(name) && !old.equals(part))
          throw new IllegalArgumentException(Tr.of("edit.incompatibleKit", name));
    }
    Map<String, String> ids = new LinkedHashMap<>();
    for (String old : fragment.nodes.keySet()) ids.put(old, "draft-" + UUID.randomUUID());
    Set<String> top = new LinkedHashSet<>();
    for (Project.Node old : fragment.nodes.values()) {
      Project.Node n = old.copy();
      n.id = ids.get(old.id);
      boolean root = !ids.containsKey(old.parent);
      n.parent = root ? parent : ids.get(old.parent);
      if (root) {
        n.x += offset;
        n.y += offset;
        if (n.x < 0 || n.y < 0 || n.x > 3060 || n.y > 3060)
          throw new IllegalArgumentException(Tr.of("edit.pasteOutOfRange"));
        top.add(n.id);
      }
      n.name = unique(target, n.parent, n.name);
      target.nodes.put(n.id, n);
    }
    for (Project.Wire old : fragment.wires) {
      Project.Wire w = old.copy();
      w.source = ids.get(old.source);
      w.target = ids.get(old.target);
      if (top.contains(w.source) && top.contains(w.target)) shiftBends(w, offset, offset);
      target.wires.add(w);
    }
    for (Project.Caption old : fragment.captions) {
      Project.Caption c = old.copy();
      c.id = "c-" + UUID.randomUUID();
      c.anchor = ids.getOrDefault(old.anchor, "");
      c.folder = ids.getOrDefault(old.folder, parent);
      if (!ids.containsKey(old.folder)) {
        c.x = Math.max(0, c.x + offset);
        c.y = Math.max(0, c.y + offset);
      }
      target.captions.add(c);
    }
    for (String part : fragment.parts) if (!target.parts.contains(part)) target.parts.add(part);
    return ids;
  }

  public static String unique(Project p, String parent, String stem) {
    for (int i = 0; ; i++) {
      String suffix = i == 0 ? "" : "_" + i;
      String name = stem.substring(0, Math.min(stem.length(), 7 - suffix.length())) + suffix;
      if (p.nodes.values().stream().noneMatch(n -> n.parent.equals(parent) && n.name.equals(name)))
        return name;
    }
  }

  private static void shiftBends(Project.Wire w, double dx, double dy) {
    for (int i = 0; i < w.bends.size(); i++) {
      Project.Point b = w.bends.get(i);
      w.bends.set(i, new Project.Point(Math.max(0, b.x() + dx), Math.max(0, b.y() + dy)));
    }
  }

  public static void move(Project p, Map<String, double[]> positions) {
    for (var entry : positions.entrySet()) {
      Project.Node n = p.nodes.get(entry.getKey());
      double[] xy = entry.getValue();
      if (n == null
          || n.id.equals("0")
          || xy.length != 2
          || !Double.isFinite(xy[0])
          || !Double.isFinite(xy[1])
          || xy[0] < 0
          || xy[0] > 3060
          || xy[1] < 0
          || xy[1] > 3060) throw new IllegalArgumentException(Tr.of("edit.coordsOutOfRange"));
    }
    for (Project.Wire w : p.wires)
      if (positions.containsKey(w.source) && positions.containsKey(w.target)) {
        Project.Node a = p.nodes.get(w.source), b = p.nodes.get(w.target);
        double dx = positions.get(a.id)[0] - a.x, dy = positions.get(a.id)[1] - a.y;
        if (Math.abs(dx - (positions.get(b.id)[0] - b.x)) < .001
            && Math.abs(dy - (positions.get(b.id)[1] - b.y)) < .001) shiftBends(w, dx, dy);
      }
    for (Project.Caption c : p.captions)
      if (positions.containsKey(c.anchor)) {
        Project.Node anchor = p.nodes.get(c.anchor);
        c.x = Math.max(0, Math.min(10000, c.x + positions.get(c.anchor)[0] - anchor.x));
        c.y = Math.max(0, Math.min(10000, c.y + positions.get(c.anchor)[1] - anchor.y));
      }
    for (var entry : positions.entrySet()) {
      Project.Node n = p.nodes.get(entry.getKey());
      n.x = entry.getValue()[0];
      n.y = entry.getValue()[1];
    }
  }

  public static Set<String> relocate(Project p, Collection<String> selected, String parent) {
    Set<String> roots = roots(p, selected), all = subtree(p, selected);
    if (all.contains(parent)) throw new IllegalArgumentException(Tr.of("edit.ownSubtree"));
    if (roots.stream().allMatch(id -> p.nodes.get(id).parent.equals(parent))) return roots;
    Project clip = fragment(p, roots);
    List<Project.Wire> external = new ArrayList<>();
    for (Project.Wire w : p.wires)
      if (all.contains(w.source) != all.contains(w.target)) external.add(w.copy());
    Map<String, String> ids = pasteMap(p, clip, parent, 0);
    Set<String> movedCaptions = new HashSet<>();
    for (Project.Caption c : clip.captions) movedCaptions.add(c.id);
    p.captions.removeIf(c -> movedCaptions.contains(c.id));
    for (String id : roots) p.remove(id);
    for (Project.Wire w : external) {
      w.source = ids.getOrDefault(w.source, w.source);
      w.target = ids.getOrDefault(w.target, w.target);
      p.wires.add(w);
    }
    Set<String> added = new LinkedHashSet<>();
    for (String id : roots) added.add(ids.get(id));
    return added;
  }

  public static void reparent(Project p, Collection<String> selected, String parent) {
    if (!parent.equals("0") && !p.nodes.containsKey(parent))
      throw new IllegalArgumentException(Tr.of("edit.noFolder"));
    Set<String> all = subtree(p, selected);
    if (all.contains(parent)) throw new IllegalArgumentException(Tr.of("edit.ownSubtree"));
    for (String id : roots(p, selected)) {
      Project.Node n = p.nodes.get(id);
      n.name = unique(p, parent, n.name);
      n.parent = parent;
      for (Project.Caption c : p.captions) if (c.anchor.equals(id)) c.folder = parent;
    }
  }

  public static Set<String> signalPath(Project p, String start) {
    Set<String> result = new LinkedHashSet<>();
    for (boolean forward : new boolean[] {true, false}) {
      Set<String> seen = new LinkedHashSet<>();
      seen.add(start);
      boolean changed;
      do {
        changed = false;
        for (Project.Wire w : p.wires)
          if (seen.contains(forward ? w.source : w.target))
            changed |= seen.add(forward ? w.target : w.source);
      } while (changed);
      result.addAll(seen);
    }
    return result;
  }
}

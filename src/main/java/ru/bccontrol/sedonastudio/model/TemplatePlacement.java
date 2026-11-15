package ru.bccontrol.sedonastudio.model;

import java.util.*;

/** Conservative whole-group packing in model coordinates; never changes existing nodes. */
public final class TemplatePlacement {
  private TemplatePlacement() {}

  private record Box(double x, double y, double width, double height) {}

  private static Box box(Project.Node n) {
    return new Box(n.x, n.y, 220, 61 + 24 * n.ports.size());
  }

  public static Project place(Project target, Project fragment, String parent) {
    Project out = fragment.copy();
    List<Project.Node> roots =
        out.nodes.values().stream().filter(n -> n.parent.equals("0")).toList();
    if (roots.isEmpty()) throw new IllegalArgumentException(Tr.of("template.noRootBlocks"));
    double minX = roots.stream().mapToDouble(n -> n.x).min().orElseThrow(),
        minY = roots.stream().mapToDouble(n -> n.y).min().orElseThrow();
    double width =
        roots.stream()
                .map(TemplatePlacement::box)
                .mapToDouble(b -> b.x + b.width)
                .max()
                .orElseThrow()
            - minX;
    double height =
        roots.stream()
                .map(TemplatePlacement::box)
                .mapToDouble(b -> b.y + b.height)
                .max()
                .orElseThrow()
            - minY;
    List<Box> occupied =
        target.nodes.values().stream()
            .filter(n -> !n.id.equals("0") && n.parent.equals(parent))
            .map(TemplatePlacement::box)
            .toList();
    SortedSet<Double> xs = new TreeSet<>(), ys = new TreeSet<>();
    xs.add(24.0);
    ys.add(24.0);
    for (Box b : occupied) {
      xs.add(Math.ceil((b.x + b.width + 24) / 12) * 12);
      ys.add(Math.ceil((b.y + b.height + 24) / 12) * 12);
    }
    for (double y : ys)
      for (double x : xs) {
        if (x + width > 3280
            || y + height > 10000
            || roots.stream().anyMatch(n -> n.x - minX + x > 3060 || n.y - minY + y > 3060))
          continue;
        boolean collision =
            occupied.stream()
                .anyMatch(
                    b ->
                        x < b.x + b.width + 12
                            && x + width + 12 > b.x
                            && y < b.y + b.height + 12
                            && y + height + 12 > b.y);
        if (collision) continue;
        double dx = x - minX, dy = y - minY;
        Set<String> ids = new HashSet<>();
        for (Project.Node n : roots) {
          ids.add(n.id);
          n.x += dx;
          n.y += dy;
        }
        for (Project.Wire wire : out.wires)
          if (ids.contains(wire.source) && ids.contains(wire.target))
            for (int i = 0; i < wire.bends.size(); i++) {
              Project.Point point = wire.bends.get(i);
              wire.bends.set(
                  i, new Project.Point(Math.max(0, point.x() + dx), Math.max(0, point.y() + dy)));
            }
        return out;
      }
    throw new IllegalArgumentException(Tr.of("template.noRoom"));
  }
}

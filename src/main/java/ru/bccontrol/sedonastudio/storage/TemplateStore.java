package ru.bccontrol.sedonastudio.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.*;

/** Shared reusable fragments; session manifest caches are never copied into the library. */
public final class TemplateStore {
  private final Path root;

  public record Entry(Path path, String name, int nodes, int wires, String error) {
    public String toString() {
      return name
          + (error.isEmpty() ? Tr.of("template.counts", nodes, wires) : Tr.of("template.damaged"));
    }
  }

  public TemplateStore(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  public Path root() {
    return root;
  }

  public static Project prepare(Project source, Collection<String> ids, String name) {
    if (name == null || name.isBlank() || name.length() > 120)
      throw new IllegalArgumentException(Tr.of("template.nameLength"));
    Project fragment = EditorCommands.fragment(source, ids);
    if (fragment.nodes.isEmpty())
      throw new IllegalArgumentException(Tr.of("template.selectBlocks"));
    for (Project.Node n : fragment.nodes.values())
      if (n.type.endsWith("Service") || n.type.contains("User") || n.type.equals("sys::App"))
        throw new IllegalArgumentException(Tr.of("template.systemComponent", n.type));
    for (String part : fragment.parts)
      if (!part.matches("[A-Za-z_][A-Za-z0-9_]*-[0-9a-fA-F]{8}"))
        throw new IllegalArgumentException(Tr.of("template.wrongExactKit", part));
    for (Project.Node n : fragment.nodes.values())
      if (!fragment.parts.contains(n.kitPart))
        throw new IllegalArgumentException(Tr.of("template.noExactKit", n.type));
    fragment.name = name.strip();
    fragment.platform = "";
    // Retain exact schemas conservatively: inheritance/dependency kits may have no own node.
    double x = Double.POSITIVE_INFINITY, y = Double.POSITIVE_INFINITY;
    for (Project.Node n : fragment.nodes.values())
      if (n.parent.equals("0")) {
        x = Math.min(x, n.x);
        y = Math.min(y, n.y);
      }
    double dx = 24 - x, dy = 24 - y;
    Set<String> top = new HashSet<>();
    for (Project.Node n : fragment.nodes.values())
      if (n.parent.equals("0")) {
        top.add(n.id);
        n.x += dx;
        n.y += dy;
      }
    for (Project.Wire w : fragment.wires)
      if (top.contains(w.source) && top.contains(w.target))
        for (int i = 0; i < w.bends.size(); i++) {
          Project.Point p = w.bends.get(i);
          w.bends.set(i, new Project.Point(Math.max(0, p.x() + dx), Math.max(0, p.y() + dy)));
        }
    return ProjectStore.decode(ProjectStore.encode(fragment));
  }

  public Path add(Project fragment) throws IOException {
    Project checked =
        TemplateRequirements.resolve(prepare(fragment, fragment.nodes.keySet(), fragment.name))
            .project();
    Files.createDirectories(root);
    Path path = root.resolve(UUID.randomUUID() + ".sedona.json");
    ProjectStore.save(checked, path);
    return path;
  }

  public List<Entry> list() throws IOException {
    if (!Files.isDirectory(root)) return List.of();
    List<Entry> out = new ArrayList<>();
    try (var files = Files.list(root)) {
      for (Path file :
          files
              .filter(
                  f ->
                      f.getFileName().toString().endsWith(".sedona.json")
                          && !Files.isSymbolicLink(f))
              .limit(2000)
              .toList()) {
        try {
          Project p = ProjectStore.load(file);
          out.add(new Entry(file, p.name, p.nodes.size(), p.wires.size(), ""));
        } catch (IOException | RuntimeException ex) {
          out.add(
              new Entry(
                  file, file.getFileName().toString(), 0, 0, String.valueOf(ex.getMessage())));
        }
      }
    }
    out.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
    return List.copyOf(out);
  }

  public Project load(Entry entry) throws IOException {
    check(entry.path);
    return ProjectStore.load(entry.path);
  }

  private void check(Path path) throws IOException {
    if (Files.isSymbolicLink(path)
        || !path.toAbsolutePath().normalize().getParent().equals(root)
        || !path.toRealPath().getParent().equals(root.toRealPath()))
      throw new IOException(Tr.of("template.outsideLibrary"));
  }

  public void rename(Entry entry, String name) throws IOException {
    if (name == null || name.isBlank() || name.length() > 120)
      throw new IllegalArgumentException(Tr.of("template.nameLength"));
    Project project = load(entry);
    project.name = name.strip();
    ProjectStore.save(project, entry.path);
  }

  public Path remove(Entry entry) throws IOException {
    check(entry.path);
    Path archive = root.resolve("deleted");
    Files.createDirectories(archive);
    Path dest = archive.resolve(UUID.randomUUID() + "-" + entry.path.getFileName());
    Files.move(entry.path, dest);
    return dest;
  }
}

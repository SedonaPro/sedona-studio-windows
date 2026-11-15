package ru.bccontrol.sedonastudio.integration.panel;

import static ru.bccontrol.panel.api.PanelData.*;

import java.util.*;
import java.util.function.BiFunction;
import ru.bccontrol.sedonastudio.model.*;

/** Conversion boundary: only immutable API DTOs leave the editor. */
public final class PanelSnapshots {
  private Project cached;
  private List<Component> components = List.of();
  private Map<String, Project.Node> nodes = Map.of();

  private void prepare(Project project) {
    if (cached == project) return;
    Map<String, Project.Node> byPath = new LinkedHashMap<>();
    List<Component> out = new ArrayList<>();
    for (Project.Node node : project.nodes.values()) {
      String path = path(project, node.id);
      List<Field> fields = new ArrayList<>();
      for (Project.Port port : node.ports)
        if (!SecretFields.matches(port.name) && !port.name.equals("meta"))
          fields.add(new Field(port.name, port.type, true, false, false));
      if (!fields.isEmpty()) {
        byPath.put(path, node);
        out.add(new Component(path, node.name, node.type, fields));
      }
    }
    out.sort(Comparator.comparing(Component::path));
    cached = project;
    nodes = Map.copyOf(byPath);
    components = List.copyOf(out);
  }

  public Snapshot create(
      Project project,
      String session,
      boolean connected,
      Set<Ref> watched,
      BiFunction<Project.Node, String, Sample> sample) {
    prepare(project);
    Map<Ref, Sample> values = new LinkedHashMap<>();
    for (Ref ref : watched) {
      Project.Node node = nodes.get(ref.componentPath());
      if (node != null
          && !SecretFields.matches(ref.field())
          && !ref.field().equals("meta")
          && node.port(ref.field()) != null) values.put(ref, sample.apply(node, ref.field()));
    }
    return new Snapshot(session, connected, components, values, Set.of(Capability.READ));
  }

  public List<String> ids(Project project, Set<Ref> watched) {
    prepare(project);
    Set<String> result = new TreeSet<>();
    for (Ref ref : watched) {
      Project.Node node = nodes.get(ref.componentPath());
      if (node != null) result.add(node.id);
    }
    return List.copyOf(result);
  }

  public static String path(Project project, String id) {
    Deque<String> names = new ArrayDeque<>();
    Set<String> seen = new HashSet<>();
    while (!id.equals("0") && seen.add(id)) {
      Project.Node n = project.nodes.get(id);
      if (n == null) break;
      names.addFirst(n.name);
      id = n.parent;
    }
    return "/" + String.join("/", names);
  }
}

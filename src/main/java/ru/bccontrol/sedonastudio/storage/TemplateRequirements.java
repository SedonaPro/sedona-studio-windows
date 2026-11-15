package ru.bccontrol.sedonastudio.storage;

import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.Tr;
import sedona.*;
import sedona.manifest.*;
import sedona.xml.XParser;

/**
 * Dependency closure uses only exact source parts and local files. No downloader or alternate
 * version.
 */
public final class TemplateRequirements {
  private TemplateRequirements() {}

  public record Result(Project project, boolean verified, String message) {}

  public static Result resolve(Project source) {
    Project result = source.copy();
    try {
      Map<String, KitPart> available = new LinkedHashMap<>();
      for (String text : source.parts) {
        KitPart part = KitPart.parse(text);
        KitPart old = available.putIfAbsent(part.name, part);
        if (old != null && !old.equals(part))
          throw new IllegalArgumentException(Tr.of("template.req.severalVersions", part.name));
      }
      Set<String> roots = new LinkedHashSet<>();
      roots.add("sys");
      for (Project.Node node : source.nodes.values()) {
        KitPart part = KitPart.parse(node.kitPart);
        if (!part.equals(available.get(part.name)))
          throw new IllegalArgumentException(Tr.of("template.req.noSourceKit", part));
        roots.add(part.name);
        for (Project.Port port : node.ports) {
          int i = port.type.indexOf("::");
          if (i > 0) roots.add(port.type.substring(0, i));
        }
      }
      Set<String> selected = new LinkedHashSet<>();
      Map<String, KitManifest> manifests = new HashMap<>();
      for (String name : roots) visit(name, available, selected, manifests);
      result.parts.clear();
      for (String text : source.parts)
        if (selected.contains(KitPart.parse(text).name)) result.parts.add(text);
      return new Result(result, true, Tr.of("template.req.verified", result.parts.size()));
    } catch (Exception ex) {
      return new Result(result, false, Tr.of("template.req.fullSetKept", ex.getMessage()));
    }
  }

  private static KitManifest visit(
      String name,
      Map<String, KitPart> available,
      Set<String> selected,
      Map<String, KitManifest> loaded)
      throws Exception {
    if (loaded.containsKey(name)) return loaded.get(name);
    KitPart part = available.get(name);
    if (part == null)
      throw new IllegalArgumentException(Tr.of("template.req.noSourceDependency", name));
    Path file = ManifestDb.toFile(part).toPath();
    if (!Files.isRegularFile(file) || Files.size(file) > ManifestArchive.MAX_XML)
      throw new IllegalArgumentException(Tr.of("template.req.noLocalManifest", part));
    String text = Files.readString(file), upper = text.toUpperCase(Locale.ROOT);
    if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY"))
      throw new IllegalArgumentException(Tr.of("template.req.dtd", part));
    KitManifest manifest = KitManifest.fromXml(XParser.make(file.toFile()).parse());
    if (!part.equals(manifest.part()))
      throw new IllegalArgumentException(Tr.of("template.req.checksum", part));
    loaded.put(name, manifest);
    selected.add(name);
    for (Depend dependency : manifest.depends) {
      KitManifest required = visit(dependency.name(), available, selected, loaded);
      if (!dependency.match(required.version, required.checksum))
        throw new IllegalArgumentException(Tr.of("template.req.dependencyUnsuitable", dependency));
    }
    return manifest;
  }
}

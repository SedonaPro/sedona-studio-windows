package ru.bccontrol.sedonastudio.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.Tr;
import sedona.*;
import sedona.manifest.*;
import sedona.xml.*;

/** Local manifests only: never calls a network downloader for missing dependencies. */
public final class LocalCatalog {
  public record Result(
      List<Project.Node> types, Map<String, List<String>> dependencies, List<String> warnings) {}

  public static Result exact(List<String> names) throws Exception {
    if (names.isEmpty()) return new Result(List.of(), Map.of(), List.of());
    KitPart[] parts = names.stream().map(KitPart::parse).toArray(KitPart[]::new);
    for (KitPart part : parts)
      if (!ManifestDb.toFile(part).isFile())
        throw new IOException(Tr.of("catalog.manifestMissing", part));
    Schema schema = Schema.load(parts);
    Map<String, List<String>> dependencies = new HashMap<>();
    for (String part : names) dependencies.put(part, List.copyOf(names));
    return new Result(SchemaCatalog.types(schema), dependencies, List.of());
  }

  public static Result load(List<String> preferred) throws Exception {
    try (InputStream in = LocalCatalog.class.getResourceAsStream("/catalog-manifests.zip")) {
      if (in != null) ManifestArchive.importManifests(in.readAllBytes());
    }
    Map<String, List<KitManifest>> installed = new TreeMap<>();
    String[] names = ManifestDb.listInstalledKits();
    if (names != null)
      for (String name : names) {
        KitPart[] parts = ManifestDb.listInstalledParts(name);
        if (parts == null) continue;
        for (KitPart part : parts)
          try {
            Path file = ManifestDb.toFile(part).toPath();
            if (Files.size(file) > ManifestArchive.MAX_XML) continue;
            String text = Files.readString(file);
            if (text.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")
                || text.toUpperCase(Locale.ROOT).contains("<!ENTITY")) continue;
            KitManifest m = KitManifest.fromXml(XParser.make(file.toFile()).parse());
            if (m.part().equals(part))
              installed.computeIfAbsent(name, k -> new ArrayList<>()).add(m);
          } catch (Exception ignored) {
          }
      }
    for (List<KitManifest> versions : installed.values())
      versions.sort((a, b) -> b.version.compareTo(a.version));
    List<Project.Node> types = new ArrayList<>();
    Map<String, List<String>> dependencies = new HashMap<>();
    List<String> warnings = new ArrayList<>();
    for (List<KitManifest> versions : installed.values())
      for (KitManifest root : versions)
        try {
          Map<String, KitManifest> selected = new LinkedHashMap<>();
          resolve(root, selected, installed, preferred);
          if (!selected.containsKey("sys")) {
            List<KitManifest> sys = installed.get("sys");
            if (sys == null || sys.isEmpty()) throw new IOException(Tr.of("catalog.noSys"));
            resolve(sys.get(0), selected, installed, preferred);
          }
          KitPart[] parts =
              selected.values().stream().map(KitManifest::part).toArray(KitPart[]::new);
          Schema schema = Schema.load(parts);
          List<String> requires = Arrays.stream(parts).map(Object::toString).toList();
          dependencies.put(root.part().toString(), requires);
          for (Project.Node n : SchemaCatalog.types(schema))
            if (n.kitPart.equals(root.part().toString())) types.add(n);
        } catch (Exception e) {
          warnings.add(root.part() + ": " + e.getMessage());
        }
    types.sort(Comparator.comparing((Project.Node n) -> n.kitPart).thenComparing(n -> n.type));
    return new Result(types, dependencies, warnings);
  }

  private static void resolve(
      KitManifest root,
      Map<String, KitManifest> selected,
      Map<String, List<KitManifest>> installed,
      List<String> preferred)
      throws Exception {
    KitManifest old = selected.get(root.name);
    if (old != null) {
      if (!old.part().equals(root.part()))
        throw new IOException(Tr.of("catalog.versionConflict", root.name));
      return;
    }
    selected.put(root.name, root);
    for (Depend dependency : root.depends) {
      KitManifest found = selected.get(dependency.name());
      if (found != null) {
        if (!dependency.match(found.version, found.checksum))
          throw new IOException(Tr.of("catalog.unsuitable", dependency));
        continue;
      }
      List<KitManifest> candidates = installed.getOrDefault(dependency.name(), List.of());
      for (KitManifest m : candidates)
        if (dependency.match(m.version, m.checksum) && preferred.contains(m.part().toString())) {
          found = m;
          break;
        }
      if (found == null)
        for (KitManifest m : candidates)
          if (dependency.match(m.version, m.checksum)) {
            found = m;
            break;
          }
      if (found == null) throw new IOException(Tr.of("catalog.noDependency", dependency));
      resolve(found, selected, installed, preferred);
    }
  }
}

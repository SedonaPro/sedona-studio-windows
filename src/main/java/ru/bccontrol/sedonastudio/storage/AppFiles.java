package ru.bccontrol.sedonastudio.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.SecretFields;
import ru.bccontrol.sedonastudio.model.Tr;
import sedona.Component;
import sedona.Kit;
import sedona.KitPart;
import sedona.Schema;
import sedona.Slot;
import sedona.Str;
import sedona.Type;
import sedona.Value;
import sedona.manifest.ManifestDb;
import sedona.offline.OfflineApp;
import sedona.offline.OfflineComponent;
import sedona.offline.OfflineLink;
import sedona.xml.XElem;
import sedona.xml.XParser;

/**
 * Sedona application files without a device: app.sax (XML) and app.sab (binary). Kit manifests come
 * from the session's manifest store; nothing is downloaded here.
 *
 * <p>An app file holds less than a project: no notes and no wire bends. It also holds secrets (user
 * credentials) which a project never does, so they are dropped on reading and left at their
 * defaults on writing - {@link #write} reports which.
 */
public final class AppFiles {
  public static final int MAX_FILE = 4 * 1024 * 1024;

  private AppFiles() {}

  public static boolean isAppFile(Path file) {
    String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    return name.endsWith(".sax") || name.endsWith(".sab");
  }

  private static boolean xml(Path file) {
    if (!isAppFile(file)) throw new IllegalArgumentException(Tr.of("appfile.extension"));
    return file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".sax");
  }

  /** Where kit manifests may come from besides the session's own store, e.g. a local SDK. */
  public interface KitSource {
    /** The build ("name-checksum") this source has for a kit name, or null. */
    String part(String kit);

    /** The manifest XML of that exact build, or null. */
    byte[] manifest(String part) throws Exception;
  }

  public static Project read(Path file) throws Exception {
    return read(file, null);
  }

  /** Reads an app file; manifests the session lacks are taken from the source when it has them. */
  public static Project read(Path file, KitSource source) throws Exception {
    boolean xml = xml(file);
    if (Files.size(file) > MAX_FILE) throw new IOException(Tr.of("appfile.tooLarge"));
    OfflineApp app;
    try {
      app = decode(file, xml, source);
    } catch (Schema.MissingKitManifestException missing) {
      if (source == null) throw new IOException(Tr.of("appfile.kitsMissing", join(missing.parts)));
      try {
        ManifestArchive.loadSchema(
            missing.parts,
            uri -> {
              byte[] xmlBytes = source.manifest(uri.substring(2, uri.length() - 4));
              if (xmlBytes == null) throw new IOException(uri);
              return xmlBytes;
            });
      } catch (Exception unavailable) {
        throw new IOException(Tr.of("appfile.kitsMissing", join(missing.parts)));
      }
      app = decode(file, xml, source);
    }
    Project result = new Project();
    String name = file.getFileName().toString();
    result.name = name.substring(0, name.length() - 4);
    for (Kit kit : app.schema.kits) result.parts.add(kit.manifest.part().toString());
    Deque<OfflineComponent> todo = new ArrayDeque<>();
    todo.add(app);
    while (!todo.isEmpty()) {
      OfflineComponent c = todo.removeFirst();
      if (result.nodes.size() >= 512) throw new IOException(Tr.of("project.blockLimit"));
      String parent = c == app ? "0" : Integer.toString(c.parent().id());
      result.nodes.put(Integer.toString(c.id()), Components.node(c, parent, result.nodes.size()));
      for (OfflineComponent kid : c.children()) todo.add(kid);
    }
    for (OfflineLink link : app.getLinks())
      result.wires.add(
          new Project.Wire(
              Integer.toString(link.fromComp.id()),
              link.fromSlot.name,
              Integer.toString(link.toComp.id()),
              link.toSlot.name));
    return result;
  }

  /**
   * Writes the project as an app file (by extension). Returns the secret configuration fields
   * ("component.field") that had to be left at their default values.
   */
  public static List<String> write(Project project, Path file) throws Exception {
    xml(file);
    KitPart[] parts = new KitPart[project.parts.size()];
    for (int i = 0; i < parts.length; i++) parts[i] = KitPart.parse(project.parts.get(i));
    Schema schema;
    try {
      schema = Schema.load(parts);
    } catch (Schema.MissingKitManifestException missing) {
      throw new IOException(Tr.of("appfile.kitsMissing", join(missing.parts)));
    }
    List<String> defaulted = new ArrayList<>();
    save(toApp(project, schema, defaulted), file);
    return defaulted;
  }

  /**
   * The project as an offline application on the given schema (which must contain the project's
   * exact kits). Secret configuration fields are left at their defaults and listed in defaulted.
   */
  public static OfflineApp toApp(Project project, Schema schema, List<String> defaulted) {
    List<String> problems = project.validate();
    if (!problems.isEmpty())
      throw new IllegalArgumentException(Tr.of("appfile.invalidProject", problems.get(0)));
    OfflineApp app = new OfflineApp(schema);
    Map<String, OfflineComponent> made = new HashMap<>();
    Project.Node root = project.nodes.get("0");
    if (root != null) fill(app, root, defaulted);
    made.put("0", app);
    Map<String, Project.Node> waiting = new LinkedHashMap<>(project.nodes);
    waiting.remove("0");
    while (!waiting.isEmpty()) {
      boolean progress = false;
      for (Project.Node n : new ArrayList<>(waiting.values())) {
        OfflineComponent parent = made.get(n.parent);
        if (parent == null) continue;
        Type type = schema.type(n.type);
        if (type == null || !type.kit.manifest.part().toString().equals(n.kitPart))
          throw new IllegalArgumentException(Tr.of("appfile.noType", n.type, n.kitPart));
        // Ids read from a device are kept; blocks added in the editor get free ids at the end.
        int id = n.id.matches("[1-9][0-9]{0,4}") ? Integer.parseInt(n.id) : -1;
        OfflineComponent c =
            id > 0 && app.lookup(id) == null
                ? new OfflineComponent(type, n.name, id)
                : new OfflineComponent(type, n.name);
        fill(c, n, defaulted);
        app.add(parent, c);
        made.put(n.id, c);
        waiting.remove(n.id);
        progress = true;
      }
      if (!progress)
        throw new IllegalArgumentException(Tr.of("project.noParent", waiting.keySet()));
    }
    app.assignIds();
    for (Project.Wire w : project.wires) {
      OfflineComponent from = made.get(w.source), to = made.get(w.target);
      Slot out = from == null ? null : from.type.slot(w.sourcePort),
          in = to == null ? null : to.type.slot(w.targetPort);
      if (out == null || in == null)
        throw new IllegalArgumentException(Tr.of("project.brokenLink", w.key()));
      app.addLink(new OfflineLink(from, out, to, in));
    }
    return app;
  }

  /**
   * The draft as an app.sab that may replace the application running on a device. deviceApp is the
   * device's current app.sab; it is the only source of secrets, which a project never holds.
   *
   * <p>Refused, so that nobody is locked out of the device by an upload: a component with secret
   * fields that the device does not have, and any change to a user or a service the device has.
   */
  /** baseline is the project as last read from that device. */
  public static byte[] forDevice(Project draft, Project baseline, byte[] deviceApp)
      throws Exception {
    KitPart[] parts = new KitPart[draft.parts.size()];
    for (int i = 0; i < parts.length; i++) parts[i] = KitPart.parse(draft.parts.get(i));
    Schema schema;
    try {
      schema = Schema.load(parts);
    } catch (Schema.MissingKitManifestException missing) {
      throw new IOException(Tr.of("appfile.kitsMissing", join(missing.parts)));
    }
    OfflineApp app = toApp(draft, schema, new ArrayList<>());
    OfflineApp device = OfflineApp.decodeAppBinary(new sedona.Buf(deviceApp));
    List<String> unsourced = new ArrayList<>();
    copySecrets(app, device, unsourced);
    if (!unsourced.isEmpty())
      throw new IllegalArgumentException(
          Tr.of("deploy.secretsWithoutSource", String.join(", ", unsourced)));
    List<String> changed = new ArrayList<>();
    guardAccess(draft, baseline, schema, changed);
    if (!changed.isEmpty())
      throw new IllegalArgumentException(Tr.of("deploy.accessChanged", String.join(", ", changed)));
    sedona.Buf out = app.encodeAppBinary();
    return java.util.Arrays.copyOf(out.bytes, out.size);
  }

  private static boolean same(OfflineComponent a, OfflineComponent b) {
    return a != null
        && b != null
        && a.type.qname.equals(b.type.qname)
        && a.type.kit.manifest.part().equals(b.type.kit.manifest.part())
        && a.name().equals(b.name());
  }

  /** Secret configuration of every component comes from the same component on the device. */
  private static void copySecrets(OfflineComponent c, OfflineApp device, List<String> unsourced) {
    OfflineComponent source = device.lookup(c.id());
    for (Slot s : c.type.slots)
      if (s.isProp() && s.isConfig() && SecretFields.matches(s.name)) {
        if (same(c, source)) c.set(s, source.get(source.type.slot(s.name)));
        else unsourced.add(c.name() + "." + s.name);
      }
    for (OfflineComponent kid : c.children()) copySecrets(kid, device, unsourced);
  }

  /**
   * Users and services must be exactly as last read from the device: they are how the device is
   * reached. The comparison is with that reading, not with the saved file - a running service may
   * hold other values than the ones it was saved with.
   */
  private static void guardAccess(
      Project draft, Project baseline, Schema schema, List<String> changed) {
    Type service = schema.type("sys::Service"), user = schema.type("sys::User");
    for (Project.Node before : baseline.nodes.values()) {
      Type type = schema.type(before.type);
      // A type the draft's kits no longer have may have been a service: treat it as one.
      boolean access =
          type == null || (service != null && type.is(service)) || (user != null && type.is(user));
      if (!access) continue;
      Project.Node after = draft.nodes.get(before.id);
      boolean equal =
          after != null
              && after.type.equals(before.type)
              && after.kitPart.equals(before.kitPart)
              && after.name.equals(before.name)
              && after.parent.equals(before.parent);
      if (equal)
        for (Project.Port port : before.ports)
          if (port.config
              && !java.util.Objects.equals(
                  before.values.get(port.name), after.values.get(port.name))) equal = false;
      if (!equal) changed.add(before.name);
    }
  }

  /** Writes the application as .sax or .sab (by extension), replacing the file in one step. */
  public static void save(OfflineApp app, Path file) throws Exception {
    boolean xml = xml(file);
    Path temp = file.resolveSibling(file.getFileName() + ".tmp");
    File target = temp.toFile();
    if (xml) app.encodeAppXml(target);
    else app.encodeAppBinary(target);
    try {
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /** Copies the block's configuration (and its position) into the component. */
  private static void fill(Component c, Project.Node n, List<String> defaulted) {
    for (Slot s : c.type.slots) {
      if (!s.isProp() || !s.isConfig()) continue;
      if (SecretFields.matches(s.name)) {
        defaulted.add(n.name + "." + s.name);
        continue;
      }
      String raw = n.values.get(s.name);
      try {
        if (s.name.equals("meta")) {
          // A block that never was on a device has no meta: Sedona's default is security group 1.
          // With no group at all the component would be invisible to every user.
          int meta = raw == null ? 1 : Integer.parseInt(raw);
          int x = Math.max(0, Math.min(255, (int) Math.round(n.x / 12.0))),
              y = Math.max(0, Math.min(255, (int) Math.round(n.y / 12.0)));
          c.setInt(s, (meta & 0xFFFF) | (x << 24) | (y << 16));
        } else if (raw != null)
          c.set(s, s.isAsStr() ? Str.make(raw) : Value.defaultForType(s.type.id).decodeString(raw));
      } catch (RuntimeException e) {
        throw new IllegalArgumentException(Tr.of("appfile.badValue", n.name, s.name, raw));
      }
    }
  }

  private static OfflineApp decode(Path file, boolean xml, KitSource source) throws Exception {
    if (!xml) return OfflineApp.decodeAppBinary(file.toFile());
    XElem root = XParser.make(file.toFile()).parse();
    XElem schema = root.elem("schema");
    if (schema != null) resolveKits(schema, source);
    return OfflineApp.decodeAppXml(root);
  }

  /** A .sax may name kits without checksums; pick the installed kit or the one known manifest. */
  private static void resolveKits(XElem schema, KitSource source) throws IOException {
    List<String> missing = new ArrayList<>();
    for (XElem kit : schema.elems("kit")) {
      String name = kit.get("name");
      String checksum = kit.get("checksum", null);
      if (checksum != null) {
        KitPart part = new KitPart(name, (int) Long.parseLong(checksum, 16));
        if (!ManifestDb.toFile(part).isFile() && source == null) missing.add(part.toString());
        continue;
      }
      // Sedona's own rule first: the kit installed in this Sedona home, if it is an SDK.
      KitPart local = null;
      try {
        local = KitPart.forLocalKit(name);
      } catch (Exception noLocalKits) {
        // Not an SDK home: fall through to the manifests.
      }
      KitPart[] known = local != null ? new KitPart[] {local} : ManifestDb.listInstalledParts(name);
      if (known.length == 0 && source != null && source.part(name) != null)
        known = new KitPart[] {KitPart.parse(source.part(name))};
      if (known.length == 0) missing.add(name);
      else if (known.length > 1) throw new IOException(Tr.of("appfile.kitAmbiguous", name));
      else kit.addAttr("checksum", Integer.toHexString(known[0].checksum));
    }
    if (!missing.isEmpty())
      throw new IOException(Tr.of("appfile.kitsMissing", String.join(", ", missing)));
  }

  private static String join(KitPart[] parts) {
    List<String> names = new ArrayList<>();
    for (KitPart part : parts) names.add(part.toString());
    return String.join(", ", names);
  }
}

package ru.bccontrol.sedonastudio.protocol;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.storage.Components;
import ru.bccontrol.sedonastudio.storage.ManifestArchive;
import ru.bccontrol.sedonastudio.storage.ProjectStore;
import sedona.*;
import sedona.dasp.*;
import sedona.sox.*;

/** One transport, one serialized owner. No background reconnect, replay or implicit app.save. */
public final class SoxDeviceSession implements DeviceSession {
  private DaspSocket socket;
  private volatile SoxClient client;
  private Schema schema;
  private final Map<String, SoxComponent> components = new LinkedHashMap<>();
  private boolean writeUncertain;
  private String host, user;
  private int port;

  public boolean connected() {
    return client != null && client.isClosed() == false;
  }

  public Project connect(String host, int port, String user, char[] password) throws Exception {
    if (client != null) throw new IllegalStateException(Tr.of("device.disconnectFirst"));
    if (port < 1 || port > 65535 || host.trim().isEmpty())
      throw new IllegalArgumentException(Tr.of("device.addressPort"));
    try {
      socket = DaspSocket.open(-1, null, DaspSocket.SESSION_QUEUING);
      client = new SoxClient(socket, InetAddress.getByName(host), port, user, new String(password));
      Hashtable<String, String> options = new Hashtable<>();
      options.put("dasp.absMax", "1024");
      client.connect(options);
      KitPart[] parts = SchemaParts.read(client);
      schema = ManifestArchive.loadSchema(parts, uri -> getFile(uri));
      client.readSchema();
      this.host = host;
      this.port = port;
      this.user = user;
      return read();
    } catch (Exception e) {
      close();
      throw e;
    } finally {
      Arrays.fill(password, '\0');
    }
  }

  /**
   * Reads a file from the device. The stock client occasionally stops receiving chunks and gives up
   * with a receive timeout; reading changes nothing on the device, so it is safe to ask again.
   * Writes are never retried.
   */
  private byte[] getFile(String uri) throws Exception {
    for (int attempt = 1; ; attempt++) {
      try {
        return getFileOnce(uri);
      } catch (java.io.IOException e) {
        boolean lostChunk = e.getMessage() != null && e.getMessage().contains("timed out");
        if (!lostChunk || attempt >= FILE_READ_ATTEMPTS || !connected()) throw e;
        fileReadRetries++;
        // The device still holds the abandoned transfer; release it or the retry gets "busy".
        sedona.sox.FileTransferClose.send(client);
      }
    }
  }

  private static final int FILE_READ_ATTEMPTS = 3;
  /** How many file reads had to be repeated in this session; for diagnostics and tests. */
  private int fileReadRetries;

  public int fileReadRetries() {
    return fileReadRetries;
  }

  private byte[] getFileOnce(String uri) throws Exception {
    Buf buffer = new Buf();
    SoxFile delegate = SoxFile.make(buffer);
    Exception[] failure = new Exception[1];
    SoxFile bounded =
        new SoxFile() {
          public int size() {
            return delegate.size();
          }

          public void open(String mode) throws java.io.IOException {
            delegate.open(mode);
          }

          public void close() {
            delegate.close();
          }

          public void read(int pos, Buf buf, int n) throws java.io.IOException {
            delegate.read(pos, buf, n);
          }

          public void write(int pos, Buf buf, int n) throws java.io.IOException {
            try {
              if (pos < 0 || n < 0 || (long) pos + n > ManifestArchive.MAX_ARCHIVE)
                throw new java.io.IOException(Tr.of("device.archiveTooLarge"));
              delegate.write(pos, buf, n);
            } catch (java.io.IOException | RuntimeException e) {
              // The stock client only prints what a chunk write throws; keep it for the caller.
              if (failure[0] == null) failure[0] = e;
              throw e;
            }
          }
        };
    client.getFile(uri, bounded, null, null);
    if (failure[0] instanceof java.io.IOException e) throw e;
    if (failure[0] != null) throw (RuntimeException) failure[0];
    return Arrays.copyOf(buffer.bytes, buffer.size);
  }

  /** A whole file of the device: "app.sab", "kits.scode". */
  public byte[] fetch(String uri) throws Exception {
    require();
    return getFile(uri);
  }

  private static String sha256(byte[] data) throws Exception {
    StringBuilder hex = new StringBuilder();
    for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(data))
      hex.append(String.format("%02x", b));
    return hex.toString();
  }

  /** Uploads under a temporary name, reads it back, and only then gives it the staged name. */
  private void stage(
      String name, byte[] data, ru.bccontrol.sedonastudio.storage.OperationJournal log)
      throws Exception {
    String part = name + ".stage.part",
        step = "STAGE " + name + " " + data.length + " " + sha256(data);
    log.attempt(step);
    client.putFile(part, SoxFile.make(new Buf(data.clone())), null, null);
    if (!Arrays.equals(getFile(part), data))
      throw new java.io.IOException(Tr.of("deploy.uploadDiffers", name));
    client.renameFile(part, name + ".stage");
    log.complete(step);
  }

  /**
   * Replaces the application on the device, and its code when given: backs up what the device has,
   * uploads the new files as staged files (verified by reading back), restarts the app, reconnects
   * and checks that the device now runs what was sent. Nothing is retried. The files the device had
   * are kept in the journal folder as saved-app-before.sab and kits-before.scode.
   *
   * <p>Returns the project read after the restart. The password is needed to log in again and is
   * wiped.
   */
  public Project deploy(byte[] app, byte[] code, java.nio.file.Path journal, char[] password)
      throws Exception {
    try {
      require();
      if (writeUncertain) throw new IllegalStateException(Tr.of("device.outcomeUnknown"));
      if (app == null && code == null) throw new IllegalArgumentException("Nothing to upload");
      String address = host, login = user;
      int udp = port;
      Files.createDirectories(journal);
      byte[] appBefore = getFile("app.sab"), codeBefore = getFile("kits.scode");
      Files.write(journal.resolve("saved-app-before.sab"), appBefore);
      Files.write(journal.resolve("kits-before.scode"), codeBefore);
      if (app != null) Files.write(journal.resolve("app-uploaded.sab"), app);
      if (code != null) Files.write(journal.resolve("kits-uploaded.scode"), code);
      ru.bccontrol.sedonastudio.storage.OperationJournal log =
          new ru.bccontrol.sedonastudio.storage.OperationJournal(journal);
      writeUncertain = true;
      try {
        // Code first: an app without its code is rejected by the VM, the reverse is not safe.
        if (code != null) stage("kits.scode", code, log);
        if (app != null) stage("app.sab", app, log);
        log.attempt("RESTART");
        SoxComponent root = client.loadApp();
        try {
          client.invoke(root, root.type.slot("restart", true), null);
        } catch (Exception dropped) {
          // The device may stop answering before it confirms the restart.
        }
        close();
        Project after = null;
        Exception last = null;
        for (int attempt = 0; attempt < 120 && after == null; attempt++) {
          Thread.sleep(500);
          try {
            after = connect(address, udp, login, password.clone());
          } catch (Exception notYet) {
            last = notYet;
          }
        }
        if (after == null)
          throw new java.io.IOException(Tr.of("deploy.noReconnect", String.valueOf(last)));
        log.complete("RESTART");
        log.attempt("VERIFY");
        if (app != null && !Arrays.equals(getFile("app.sab"), app))
          throw new IllegalStateException(Tr.of("deploy.notInstalled", "app.sab"));
        if (code != null && !Arrays.equals(getFile("kits.scode"), code))
          throw new IllegalStateException(Tr.of("deploy.notInstalled", "kits.scode"));
        log.complete("VERIFY");
        writeUncertain = false;
        return after;
      } catch (Exception failed) {
        throw new IllegalStateException(
            Tr.of("deploy.interrupted", failed.getMessage(), journal), failed);
      }
    } finally {
      Arrays.fill(password, '\0');
    }
  }

  private void require() {
    if (!connected()) throw new IllegalStateException(Tr.of("device.notConnected"));
  }

  public Project read() throws Exception {
    require();
    KitPart[] currentParts = SchemaParts.read(client);
    if (currentParts.length != schema.kits.length)
      throw new IllegalStateException(Tr.of("device.schemaChanged"));
    for (int i = 0; i < currentParts.length; i++)
      if (!currentParts[i].equals(schema.kits[i].manifest.part()))
        throw new IllegalStateException(Tr.of("device.schemaChanged"));
    Project result = new Project();
    result.name = Tr.of("device.defaultName");
    result.platform = client.readVersion().platformId;
    for (Kit k : schema.kits) result.parts.add(k.manifest.part().toString());
    components.clear();
    Set<Integer> seen = new HashSet<>();
    Deque<Integer> todo = new ArrayDeque<>();
    todo.add(0);
    Set<String> links = new HashSet<>();
    while (!todo.isEmpty()) {
      int id = todo.removeFirst();
      if (!seen.add(id)) continue;
      if (seen.size() > 512) throw new IllegalStateException(Tr.of("device.readLimit"));
      SoxComponent c = client.load(id);
      client.update(
          c, SoxComponent.TREE | SoxComponent.CONFIG | SoxComponent.RUNTIME | SoxComponent.LINKS);
      components.put(Integer.toString(id), c);
      result.nodes.put(
          Integer.toString(id),
          Components.node(c, c.id() == 0 ? "0" : Integer.toString(c.parentId()), seen.size() - 1));
      for (int child : c.childrenIds()) todo.add(child);
      for (Link l : client.links(c)) {
        String key = l.toString();
        if (!links.add(key)) continue;
        SoxComponent a = client.load(l.fromCompId), b = client.load(l.toCompId);
        result.wires.add(
            new Project.Wire(
                Integer.toString(l.fromCompId),
                a.type.slot(l.fromSlotId).name,
                Integer.toString(l.toCompId),
                b.type.slot(l.toSlotId).name));
      }
    }
    writeUncertain = false;
    return result;
  }

  public Map<String, String> sample(String id) throws Exception {
    require();
    SoxComponent c = components.get(id);
    if (c == null) throw new IllegalArgumentException(Tr.of("device.noComponent"));
    client.update(c, SoxComponent.CONFIG | SoxComponent.RUNTIME);
    Map<String, String> result = new LinkedHashMap<>();
    for (Slot s : c.type.slots)
      if (s.isProp() && !ScalarValues.sensitive(s.name)) {
        Value v = c.get(s);
        if (v != null) result.put(s.name, v.encodeString());
      }
    return result;
  }

  public int downloadManifests() throws Exception {
    require();
    KitPart[] parts = SchemaParts.read(client);
    if (parts.length != schema.kits.length)
      throw new IllegalStateException(Tr.of("device.schemaChanged"));
    for (int i = 0; i < parts.length; i++)
      if (!parts[i].equals(schema.kits[i].manifest.part()))
        throw new IllegalStateException(Tr.of("device.schemaChanged"));
    schema = ManifestArchive.loadSchema(parts, uri -> getFile(uri), true);
    return parts.length;
  }

  public List<Project.Node> catalog() {
    require();
    return ru.bccontrol.sedonastudio.storage.SchemaCatalog.types(schema);
  }

  public List<String> plan(Project before, Project after) {
    List<String> steps = new ArrayList<>();
    for (Project.Wire w : before.wires)
      if (!contains(after.wires, w)) steps.add(Tr.of("device.plan.removeLink", w.key()));
    for (Project.Node n : after.nodes.values()) {
      Project.Node old = before.nodes.get(n.id);
      if (old == null) {
        steps.add(Tr.of("device.plan.create", n.name, n.type));
        continue;
      }
      if (!old.type.equals(n.type)
          || !old.parent.equals(n.parent)
          || !old.kitPart.equals(n.kitPart))
        throw new IllegalArgumentException(Tr.of("device.plan.retypeUnsupported"));
      if (!old.name.equals(n.name)) steps.add(Tr.of("device.plan.rename", old.name, n.name));
      if (old.x != n.x || old.y != n.y) steps.add(Tr.of("device.plan.position", n.name));
      for (Project.Port p : n.ports)
        if (!p.readonly && !Objects.equals(n.values.get(p.name), old.values.get(p.name)))
          steps.add(Tr.of("device.plan.field", n.name, p.name, n.values.get(p.name)));
    }
    for (Project.Wire w : after.wires)
      if (!contains(before.wires, w)) steps.add(Tr.of("device.plan.addLink", w.key()));
    for (Project.Node n : before.nodes.values())
      if (!after.nodes.containsKey(n.id)) steps.add(Tr.of("device.plan.removeBlock", n.name));
    return steps;
  }

  private static boolean contains(List<Project.Wire> list, Project.Wire wire) {
    for (Project.Wire w : list) if (w.key().equals(wire.key())) return true;
    return false;
  }

  private void identity(SoxComponent c) throws Exception {
    SchemaParts.assertIdentity(client, c);
  }

  private sedona.Type exact(Project.Node n) {
    sedona.Type t = schema.type(n.type);
    if (t == null
        || !t.kit.manifest.part().toString().equals(n.kitPart)
        || !t.isaComponent()
        || t.isAbstract())
      throw new IllegalArgumentException(Tr.of("device.noExactType", n.type, n.kitPart));
    for (Slot s : t.configProps())
      if (ScalarValues.sensitive(s.name))
        throw new IllegalArgumentException(Tr.of("device.credentialsUnsupported"));
    return t;
  }

  private int meta(Project.Node n, int flags) {
    int x = (int) Math.round(n.x / 12), y = (int) Math.round(n.y / 12);
    if (x < 0 || x > 255 || y < 0 || y > 255)
      throw new IllegalArgumentException(Tr.of("device.positionOutOfRange"));
    return (x << 24) | (y << 16) | (flags & 65535);
  }

  private Link link(Project.Wire w, Map<String, SoxComponent> actual) {
    SoxComponent a = actual.get(w.source), b = actual.get(w.target);
    if (a == null || b == null) throw new IllegalArgumentException(Tr.of("device.noLinkBlock"));
    return new Link(a.id(), a.type.slot(w.sourcePort).id, b.id(), b.type.slot(w.targetPort).id);
  }

  private void checkEdges(Project before, Project fresh) {
    Set<String> a = new HashSet<>(), b = new HashSet<>();
    for (Project.Wire w : before.wires) a.add(w.key());
    for (Project.Wire w : fresh.wires) b.add(w.key());
    if (!a.equals(b)) throw new IllegalStateException(Tr.of("device.linksChanged"));
  }

  public Project apply(Project before, Project after, boolean save, java.nio.file.Path journal)
      throws Exception {
    require();
    if (writeUncertain) throw new IllegalStateException(Tr.of("device.outcomeUnknown"));
    List<String> errors = after.validate();
    if (!errors.isEmpty()) throw new IllegalArgumentException(errors.get(0));
    List<String> steps = plan(before, after);
    if (steps.isEmpty()) return read();
    for (Project.Node n : after.nodes.values()) {
      Project.Node old = before.nodes.get(n.id);
      if (n.id.equals("0") && old != null && !old.name.equals(n.name))
        throw new IllegalArgumentException(Tr.of("device.rootName"));
      if (old == null || !old.name.equals(n.name))
        for (Project.Node occupied : before.nodes.values())
          if (!occupied.id.equals("0")
              && !occupied.id.equals(n.id)
              && occupied.parent.equals(n.parent)
              && occupied.name.equals(n.name))
            throw new IllegalArgumentException(Tr.of("device.nameTaken", n.name));
    }

    // Entire plan is validated against a fresh device snapshot before the first mutation.
    Project fresh = read();
    if (!fresh.parts.equals(before.parts) || !fresh.nodes.keySet().equals(before.nodes.keySet()))
      throw new IllegalStateException(Tr.of("device.setChanged"));
    checkEdges(before, fresh);
    for (Project.Node old : before.nodes.values()) {
      Project.Node now = fresh.nodes.get(old.id);
      if (!old.name.equals(now.name)
          || !old.parent.equals(now.parent)
          || !old.type.equals(now.type))
        throw new IllegalStateException(Tr.of("device.blockChanged", old.name));
    }
    for (Project.Node n : after.nodes.values()) {
      Project.Node old = before.nodes.get(n.id);
      sedona.Type type = old == null ? exact(n) : schema.type(n.type);
      if (old == null || old.x != n.x || old.y != n.y) meta(n, 0);
      if (old != null && !old.name.equals(n.name)) identity(components.get(n.id));
      for (Project.Port p : n.ports)
        if (!p.readonly
            && (old == null
                ? p.config
                : !Objects.equals(n.values.get(p.name), old.values.get(p.name)))) {
          Slot slot = type.slot(p.name);
          for (Project.Wire wire : after.wires)
            if (wire.target.equals(n.id) && wire.targetPort.equals(p.name) && old != null)
              throw new IllegalArgumentException(Tr.of("device.inputLinked", n.name, p.name));

          ScalarValues.parse(slot, n.values.get(p.name));
          if (old != null
              && !Objects.equals(fresh.nodes.get(n.id).values.get(p.name), old.values.get(p.name)))
            throw new IllegalStateException(Tr.of("device.valueChanged", old.name, p.name));
        }
    }
    for (Project.Wire w : after.wires)
      if (!contains(before.wires, w)) {
        Project.Node a = after.nodes.get(w.source), b = after.nodes.get(w.target);
        Project.Port ap = a.port(w.sourcePort), bp = b.port(w.targetPort);
        if (ap == null || bp == null || !bp.input || bp.readonly || !ap.type.equals(bp.type))
          throw new IllegalArgumentException(Tr.of("device.badLink"));
      }
    for (Project.Node n : before.nodes.values())
      if (!after.nodes.containsKey(n.id)) {
        if (n.id.equals("0") || n.type.endsWith("Service") || n.type.contains("User"))
          throw new IllegalArgumentException(Tr.of("device.systemRemoval"));
        identity(components.get(n.id));
      }
    Files.createDirectories(journal);
    // GET only: preserve the existing saved image without invoking App.save.
    Files.write(journal.resolve("saved-app-before.sab"), getFile("app.sab"));
    Files.write(journal.resolve("kits-before.scode"), getFile("kits.scode"));
    List<KitPart> backupParts = new ArrayList<>();
    for (Kit k : schema.kits) backupParts.add(k.manifest.part());
    Files.write(
        journal.resolve("manifests-before.zip"),
        ManifestArchive.create(backupParts.toArray(new KitPart[0])));
    ProjectStore.save(fresh, journal.resolve("before.sedona.json"));
    ProjectStore.save(after, journal.resolve("requested.sedona.json"));
    ru.bccontrol.sedonastudio.storage.OperationJournal audit =
        new ru.bccontrol.sedonastudio.storage.OperationJournal(journal);
    Map<String, SoxComponent> actual = new LinkedHashMap<>(components);
    writeUncertain = true;
    try {
      for (Project.Wire w : before.wires)
        if (!contains(after.wires, w)) {
          SoxComponent target = actual.get(w.target);
          identity(target);
          audit.attempt("UNLINK " + w.key());
          client.unlink(link(w, actual));
          audit.complete("UNLINK " + w.key());
        }
      List<Project.Node> added = new ArrayList<>();
      for (Project.Node n : after.nodes.values()) if (!before.nodes.containsKey(n.id)) added.add(n);
      while (!added.isEmpty()) {
        boolean progress = false;
        for (Iterator<Project.Node> it = added.iterator(); it.hasNext(); ) {
          Project.Node n = it.next();
          SoxComponent parent = actual.get(n.parent);
          if (parent == null) continue;
          identity(parent);
          sedona.Type t = exact(n);
          Slot[] props = t.configProps();
          Value[] config = new Value[props.length];
          for (int k = 0; k < props.length; k++) {
            Slot s = props[k];
            config[k] =
                s.name.equals("meta")
                    ? Int.make(
                        meta(
                            n,
                            Integer.parseInt(
                                n.values.getOrDefault(
                                    "meta", Integer.toString(((Int) s.def()).val)))))
                    : n.values.containsKey(s.name) && !s.facets.getb("readonly", false)
                        ? ScalarValues.parse(s, n.values.get(s.name))
                        : s.def();
          }
          audit.attempt("ADD " + n.id + " " + n.name + " " + n.type);
          SoxComponent c = client.add(parent, t, n.name, config);
          actual.put(n.id, c);
          client.update(c, SoxComponent.CONFIG | SoxComponent.TREE | SoxComponent.RUNTIME);
          for (int k = 0; k < props.length; k++)
            if (!client.readProp(c, props[k]).equals(config[k]))
              throw new IllegalStateException(Tr.of("device.readbackNewBlock"));
          audit.complete("ADD " + n.id + " -> " + c.id());
          it.remove();
          progress = true;
        }
        if (!progress) throw new IllegalStateException(Tr.of("device.newBlockParent"));
      }
      for (Project.Node n : after.nodes.values()) {
        Project.Node old = before.nodes.get(n.id);
        if (old == null) continue;
        SoxComponent c = actual.get(n.id);
        if (!old.name.equals(n.name)) {
          identity(c);
          audit.attempt("RENAME " + n.id + " " + n.name);
          client.rename(c, n.name);
          client.update(c, SoxComponent.TREE);
          if (!c.name().equals(n.name))
            throw new IllegalStateException(Tr.of("device.readbackName"));
          audit.complete("RENAME " + n.id);
        }
        if (old.x != n.x || old.y != n.y) {
          identity(c);
          Slot slot = c.type.slot("meta");
          int value = ((Int) client.readProp(c, slot)).val;
          int expected = Integer.parseInt(old.values.getOrDefault("meta", "0"));
          if ((value & 0xffff0000) != (expected & 0xffff0000))
            throw new IllegalStateException(Tr.of("device.positionChanged"));
          Value desired = Int.make(meta(n, value));
          audit.attempt("WRITE " + n.id + "." + slot.name);
          client.write(c, slot, desired);
          if (!desired.equals(client.readProp(c, slot)))
            throw new IllegalStateException(Tr.of("device.readbackPosition"));
          audit.complete("MOVE " + n.id);
        }
        for (Project.Port p : n.ports)
          if (!p.readonly && !Objects.equals(n.values.get(p.name), old.values.get(p.name))) {
            identity(c);
            Slot slot = c.type.slot(p.name);
            Value expected = ScalarValues.parse(slot, old.values.get(p.name)),
                desired = ScalarValues.parse(slot, n.values.get(p.name));
            if (!expected.equals(client.readProp(c, slot)))
              throw new IllegalStateException(Tr.of("device.valueChangedBeforeWrite"));
            audit.attempt("WRITE " + n.id + "." + slot.name);
            client.write(c, slot, desired);
            if (!desired.equals(client.readProp(c, slot)))
              throw new IllegalStateException(Tr.of("device.readbackValue"));
            audit.complete("WRITE " + n.id + "." + p.name);
          }
      }
      for (Project.Wire w : after.wires)
        if (!contains(before.wires, w)) {
          identity(actual.get(w.source));
          identity(actual.get(w.target));
          audit.attempt("LINK " + w.key());
          client.link(link(w, actual));
          audit.complete("LINK " + w.key());
        }
      List<Project.Node> deleted = new ArrayList<>();
      for (Project.Node n : before.nodes.values())
        if (!after.nodes.containsKey(n.id)) deleted.add(n);
      deleted.sort((a, b) -> Integer.compare(depth(before, b), depth(before, a)));
      for (Project.Node n : deleted) {
        SoxComponent c = actual.get(n.id);
        identity(c);
        client.update(c, SoxComponent.TREE);
        if (c.childrenIds().length != 0)
          throw new IllegalStateException(Tr.of("device.folderNotEmpty"));
        audit.attempt("DELETE " + n.id);
        client.delete(c);
        audit.complete("DELETE " + n.id);
      }
      Project result = read();
      verifyResult(before, after, result, actual);
      for (Project.Caption old : after.captions) {
        Project.Caption c = old.copy();
        c.folder = actual.containsKey(old.folder) ? Integer.toString(actual.get(old.folder).id()) : "0";
        c.anchor = actual.containsKey(old.anchor) ? Integer.toString(actual.get(old.anchor).id()) : "";
        result.captions.add(c);
      }
      if (save) {
        SoxComponent root = components.get("0");
        identity(root);
        audit.attempt("APP_SAVE");
        client.invoke(root, root.type.slot("save"), null);
        audit.complete("APP_SAVE_REQUESTED (persistence not reboot-tested)");
      }
      Files.write(journal.resolve("completed.log"), audit.entries(), StandardCharsets.UTF_8);
      ProjectStore.save(result, journal.resolve("readback.sedona.json"));
      return result;
    } catch (Exception e) {
      writeUncertain = true;
      Files.write(journal.resolve("partial.log"), audit.entries(), StandardCharsets.UTF_8);
      Files.write(
          journal.resolve("failure.txt"),
          (e.getClass().getSimpleName()
                  + ": "
                  + String.valueOf(e.getMessage())
                  + "\nNo automatic retry or rollback. Re-read device before further writes.")
              .getBytes(StandardCharsets.UTF_8));
      throw new IllegalStateException(Tr.of("device.interrupted", journal), e);
    }
  }

  private void verifyResult(
      Project before, Project after, Project result, Map<String, SoxComponent> actual) {
    Set<String> expectedEdges = new HashSet<>(), realEdges = new HashSet<>();
    for (Project.Wire w : after.wires) {
      Project.Wire actualWire =
          new Project.Wire(
              Integer.toString(actual.get(w.source).id()),
              w.sourcePort,
              Integer.toString(actual.get(w.target).id()),
              w.targetPort);
      expectedEdges.add(actualWire.key());
      for (Project.Wire real : result.wires)
        if (real.key().equals(actualWire.key())) real.bends.addAll(w.bends);
    }
    for (Project.Wire w : result.wires) realEdges.add(w.key());
    if (!expectedEdges.equals(realEdges))
      throw new IllegalStateException(Tr.of("device.linksDiffer"));
    for (Project.Node wanted : after.nodes.values()) {
      Project.Node old = before.nodes.get(wanted.id),
          found = result.nodes.get(Integer.toString(actual.get(wanted.id).id()));
      if (found == null || !wanted.name.equals(found.name) || !wanted.type.equals(found.type))
        throw new IllegalStateException(Tr.of("device.blockDiffers"));
      found.note = wanted.note;
      found.width = wanted.width;
      found.icon = wanted.icon;
      found.info = wanted.info;
      found.infoSource = wanted.infoSource;
      if (old == null && !wanted.id.equals("0")) {
        int desiredFlags =
            Integer.parseInt(
                    wanted.values.getOrDefault(
                        "meta",
                        Integer.toString(((Int) schema.type(wanted.type).slot("meta").def()).val)))
                & 65535;
        int foundFlags = Integer.parseInt(found.values.getOrDefault("meta", "1")) & 65535;
        if (desiredFlags != foundFlags)
          throw new IllegalStateException(Tr.of("device.groupsDiffer", wanted.name));
      }

      for (Project.Port port : wanted.ports)
        if (!port.readonly
            && (old == null
                ? port.config
                : !Objects.equals(wanted.values.get(port.name), old.values.get(port.name)))) {
          Slot slot = schema.type(wanted.type).slot(port.name);
          if (!ScalarValues.parse(slot, wanted.values.get(port.name))
              .equals(ScalarValues.parse(slot, found.values.get(port.name))))
            throw new IllegalStateException(Tr.of("device.valueDiffers", wanted.name, port.name));
        }
    }
    for (Project.Node old : before.nodes.values())
      if (!after.nodes.containsKey(old.id) && result.nodes.containsKey(old.id))
        throw new IllegalStateException(Tr.of("device.stillExists"));
  }

  private int depth(Project p, Project.Node n) {
    int d = 0;
    Set<String> seen = new HashSet<>();
    while (n != null && seen.add(n.id) && !n.id.equals("0")) {
      d++;
      n = p.nodes.get(n.parent);
    }
    return d;
  }

  public void close() {
    if (client != null)
      try {
        client.close();
      } catch (Exception ignored) {
      }
    if (socket != null)
      try {
        socket.close();
      } catch (Exception ignored) {
      }
    client = null;
    socket = null;
    schema = null;
    components.clear();
    writeUncertain = false;
  }
}

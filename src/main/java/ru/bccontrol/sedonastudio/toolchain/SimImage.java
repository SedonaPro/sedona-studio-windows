package ru.bccontrol.sedonastudio.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.storage.AppFiles;
import ru.bccontrol.sedonastudio.storage.ManifestArchive;
import sedona.KitPart;
import sedona.Schema;
import sedona.Type;
import sedona.Value;
import sedona.offline.OfflineApp;
import sedona.offline.OfflineComponent;

/**
 * An image of a project that can run in the local VM and be reached over SOX: the project itself
 * plus whatever a bare diagram lacks - the platform, user and SOX services - and one user that
 * exists only for this run. The project is not changed and never sees the credentials.
 */
public final class SimImage {
  /** How to reach the simulator that runs this image. The password is random for every build. */
  public record Access(int port, String user, char[] password, List<String> parts) {}

  private static final String[] REQUIRED = {"sys", "inet", "sox"};

  private SimImage() {}

  public static Access build(Toolchain toolchain, Project project, Path folder, int port)
      throws Exception {
    Sdk sdk = toolchain.sdk();
    // Exact kits of the project, plus the SDK's builds of the kits SOX needs. "sys" goes first.
    List<String> parts = new ArrayList<>(project.parts);
    for (String kit : REQUIRED)
      if (parts.stream().noneMatch(p -> p.startsWith(kit + "-"))) {
        String part = sdk.kitPart(kit);
        if (part == null) throw new IOException(Tr.of("toolchain.kitsNotInSdk", kit, sdk.home));
        if (kit.equals("sys")) parts.add(0, part);
        else parts.add(part);
      }
    // The VM reports every kit in its code, so the app and the session must know them all.
    parts = toolchain.withDependencies(parts);
    List<String> absent = new ArrayList<>();
    for (String part : parts) if (sdk.kitFile(part) == null) absent.add(part);
    if (!absent.isEmpty())
      throw new IOException(Tr.of("toolchain.kitsNotInSdk", String.join(", ", absent), sdk.home));
    KitPart[] kitParts = new KitPart[parts.size()];
    for (int i = 0; i < kitParts.length; i++) kitParts[i] = KitPart.parse(parts.get(i));
    // Manifests the session does not have yet are taken from the SDK, never from the network.
    Schema schema =
        ManifestArchive.loadSchema(
            kitParts,
            uri -> {
              byte[] xml = sdk.kitSource().manifest(uri.substring(2, uri.length() - 4));
              if (xml == null) throw new IOException(uri);
              return xml;
            });
    OfflineApp app = AppFiles.toApp(project, schema, new ArrayList<>());
    OfflineComponent services = app.child("service");
    if (services == null || !services.type.qname.equals("sys::Folder")) {
      services =
          app.add(app, new OfflineComponent(type(schema, "sys::Folder"), free(app, "service")));
      // Below the diagram, so the added folder does not push the user's blocks around.
      double bottom = 0;
      for (Project.Node n : project.nodes.values())
        if (n.parent.equals("0") && !n.id.equals("0")) bottom = Math.max(bottom, n.y);
      int row = (int) Math.min(255, Math.round(bottom / 12.0) + 20);
      services.setInt(services.slot("meta", true), 1 | (5 << 24) | (row << 16));
    }
    if (find(app, type(schema, "sys::PlatformService")) == null)
      app.add(
          services,
          new OfflineComponent(type(schema, "sys::PlatformService"), free(services, "plat")));
    OfflineComponent users = find(app, type(schema, "sys::UserService"));
    if (users == null)
      users =
          app.add(
              services,
              new OfflineComponent(type(schema, "sys::UserService"), free(services, "users")));
    OfflineComponent sox = find(app, type(schema, "sox::SoxService"));
    if (sox == null)
      sox =
          app.add(
              services,
              new OfflineComponent(type(schema, "sox::SoxService"), free(services, "sox")));
    sox.setInt(sox.slot("port", true), port);
    String user = free(users, "simusr");
    char[] password = new char[24];
    SecureRandom random = new SecureRandom();
    for (int i = 0; i < password.length; i++)
      password[i] = "abcdefghijkmnpqrstuvwxyz23456789".charAt(random.nextInt(32));
    OfflineComponent account =
        app.add(users, new OfflineComponent(type(schema, "sys::User"), user));
    byte[] digest =
        MessageDigest.getInstance("SHA-1")
            .digest((user + ":" + new String(password)).getBytes(StandardCharsets.UTF_8));
    account.set(
        account.slot("cred", true),
        Value.defaultForType(account.slot("cred", true).type.id)
            .decodeString(Base64.getEncoder().encodeToString(digest)));
    account.setInt(account.slot("perm", true), 0x7fffffff);
    account.setInt(account.slot("prov", true), 0xff);
    app.assignIds();
    Files.createDirectories(folder);
    AppFiles.save(app, folder.resolve("app.sab"));
    toolchain.buildCode(parts, folder);
    return new Access(port, user, password, List.copyOf(parts));
  }

  private static Type type(Schema schema, String qname) {
    Type type = schema.type(qname);
    if (type == null) throw new IllegalStateException(Tr.of("appfile.noType", qname, "SDK"));
    return type;
  }

  private static OfflineComponent find(OfflineComponent from, Type wanted) {
    if (from.type.is(wanted)) return from;
    for (OfflineComponent kid : from.children()) {
      OfflineComponent found = find(kid, wanted);
      if (found != null) return found;
    }
    return null;
  }

  /** A child name that is not taken yet (Sedona names are at most 7 characters). */
  private static String free(OfflineComponent parent, String wanted) {
    if (parent.child(wanted) == null) return wanted;
    for (int i = 1; i < 100; i++) {
      String name = wanted.substring(0, Math.min(wanted.length(), 5)) + i;
      if (parent.child(name) == null) return name;
    }
    throw new IllegalStateException(Tr.of("project.duplicateName", wanted));
  }
}

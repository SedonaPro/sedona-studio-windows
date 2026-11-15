package ru.bccontrol.sedonastudio.storage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Tr;

/** Persistent session profiles; passwords are DPAPI blobs scoped to the current Windows user. */
public final class SessionStore {
  public record Profile(
      String id, String name, String host, int port, String user, String password) {
    public String toString() {
      String endpoint = host.isBlank() ? Tr.of("session.local") : host + ":" + port;
      return name
          + (name.equals(endpoint) ? "" : " · " + endpoint)
          + (id.equals("local") ? "" : " [" + id.substring(0, Math.min(8, id.length())) + "]");
    }
  }

  public static Path root() {
    return Paths.get(System.getProperty("studio.root", System.getProperty("studio.data")))
        .toAbsolutePath()
        .normalize();
  }

  public static String active() {
    return System.getProperty("studio.session", "local");
  }

  public static Path directory(String id) {
    if (!id.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException(Tr.of("session.id"));
    return root().resolve("sessions").resolve(id);
  }

  public static Profile read(String id) throws IOException {
    Path f = directory(id).resolve("profile.json");
    if (!Files.exists(f))
      return new Profile(
          id, id.equals("local") ? Tr.of("session.localLibrary") : id, "", 11876, "admin", "");
    Map<String, Object> m = Json.object(Files.readString(f));
    return new Profile(
        id,
        (String) m.get("name"),
        (String) m.get("host"),
        ((Number) m.get("port")).intValue(),
        (String) m.get("user"),
        (String) m.getOrDefault("passwordDpapi", ""));
  }

  public static List<Profile> list() throws IOException {
    List<Profile> out = new ArrayList<>();
    out.add(read("local"));
    Path d = root().resolve("sessions");
    if (Files.isDirectory(d))
      try (var stream = Files.list(d)) {
        for (Path f : stream.toList())
          if (Files.isDirectory(f) && !f.getFileName().toString().equals("local"))
            out.add(read(f.getFileName().toString()));
      }
    return out;
  }

  public static List<Profile> matching(String host, int port, String user) throws IOException {
    return list().stream()
        .filter(
            p ->
                !p.host.isBlank()
                    && p.host.equalsIgnoreCase(host)
                    && p.port == port
                    && p.user.equals(user))
        .toList();
  }

  public static Path remove(String id) throws IOException {
    if (id.equals("local") || id.equals(active()))
      throw new IOException(Tr.of("session.cannotRemove"));
    Path source = directory(id).toAbsolutePath().normalize();
    Path sessions = root().resolve("sessions").toRealPath();
    if (Files.isSymbolicLink(source)
        || !source.toRealPath().startsWith(sessions)
        || source.equals(sessions)) throw new IOException(Tr.of("session.badPath"));
    try (var channel =
        java.nio.channels.FileChannel.open(
            source.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      try (var lock = channel.tryLock()) {
        if (lock == null) throw new IOException(Tr.of("session.openElsewhere"));
      }
    } catch (java.nio.channels.OverlappingFileLockException e) {
      throw new IOException(Tr.of("session.openNow"), e);
    }
    Path trash = root().resolve("deleted-sessions").toAbsolutePath().normalize();
    Files.createDirectories(trash);
    if (Files.isSymbolicLink(trash) || !trash.toRealPath().startsWith(root().toRealPath()))
      throw new IOException(Tr.of("session.badArchiveFolder"));
    Path target = trash.resolve(id + "-" + System.currentTimeMillis()).normalize();
    if (!target.startsWith(root().toAbsolutePath().normalize())
        || !target.getParent().equals(trash))
      throw new IOException(Tr.of("session.badArchiveFolder"));
    Files.move(source, target);
    return target;
  }

  public static String manifestSummary(String id) throws IOException {
    Path dir = directory(id),
        set = dir.resolve("manifest-set.json"),
        cache = dir.resolve("cache/manifests");
    StringBuilder out = new StringBuilder(Tr.of("session.folder", cache));
    if (Files.exists(set))
      for (Object part : (List<?>) Json.object(Files.readString(set)).get("parts"))
        out.append(part).append('\n');
    else out.append(Tr.of("session.noSchemaYet"));
    long count = 0;
    if (Files.isDirectory(cache))
      try (var walk = Files.walk(cache)) {
        count = walk.filter(p -> p.toString().endsWith(".xml")).count();
      }
    out.append(Tr.of("session.xmlInCache")).append(count);
    return out.toString();
  }

  public static Profile create(String name) throws IOException {
    if (name.isBlank() || name.length() > 120)
      throw new IllegalArgumentException(Tr.of("session.nameLength"));
    Profile p = new Profile(UUID.randomUUID().toString(), name, "", 11876, "admin", "");
    save(p);
    return p;
  }

  public static void save(Profile p) throws IOException {
    if (p.port < 1 || p.port > 65535) throw new IllegalArgumentException(Tr.of("session.udpPort"));
    Path dir = directory(p.id);
    Files.createDirectories(dir);
    Path tmp = Files.createTempFile(dir, "profile-", ".tmp");
    Files.writeString(
        tmp,
        Json.write(
            ProjectStore.map(
                "version",
                1,
                "name",
                p.name,
                "host",
                p.host,
                "port",
                p.port,
                "user",
                p.user,
                "passwordDpapi",
                p.password)),
        StandardCharsets.UTF_8);
    try {
      Files.move(
          tmp,
          dir.resolve("profile.json"),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, dir.resolve("profile.json"), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  public record Launch(ProcessHandle process, Path ready) {}

  public static Launch open(String id) throws Exception {
    return launch(id, List.of());
  }

  public static Launch openForConnection(String id, char[] password) throws Exception {
    String token = prepareConnection(id, password);
    try {
      return launch(id, List.of("--connect-request=" + token));
    } catch (Exception e) {
      Files.deleteIfExists(directory(id).resolve("connect-" + token + ".json"));
      throw e;
    }
  }

  public static String prepareConnection(String id, char[] password) throws Exception {
    String token = UUID.randomUUID().toString();
    Files.writeString(
        directory(id).resolve("connect-" + token + ".json"),
        Json.write(
            ProjectStore.map(
                "created", System.currentTimeMillis(), "passwordDpapi", encrypt(id, password))),
        StandardOpenOption.CREATE_NEW);
    return token;
  }

  public static char[] consumeConnection(String id, String token) throws Exception {
    if (!token.matches("[0-9a-f-]{36}")) throw new IOException(Tr.of("session.badConfirmation"));
    Path path = directory(id).resolve("connect-" + token + ".json");
    String payload;
    try {
      if (Files.size(path) > 8192) throw new IOException(Tr.of("session.badRequest"));
      payload = Files.readString(path);
    } finally {
      Files.deleteIfExists(path);
    }
    Map<String, Object> request = Json.object(payload);
    long age = System.currentTimeMillis() - ((Number) request.get("created")).longValue();
    if (age < 0 || age > 120000) throw new IOException(Tr.of("session.confirmationExpired"));
    return decrypt(new Profile(id, "", "", 11876, "", (String) request.get("passwordDpapi")));
  }

  public static Launch launch(String id, List<String> arguments) throws Exception {
    read(id);
    Files.createDirectories(directory(id));
    ProcessHandle existing = running(id);
    if (existing != null) return activate(id, arguments);

    Path java =
        Paths.get(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java");
    String jar =
        Paths.get(SessionStore.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toString();
    List<String> command =
        new ArrayList<>(
            List.of(
                java.toString(),
                "--add-modules",
                "javafx.controls,javafx.swing",
                "-Xmx768m",
                "-Dstudio.root=" + root(),
                "-Dstudio.data=" + root(),
                "-Dstudio.session=" + id,
                "-jar",
                jar));
    // The new window needs what this one was started with. A packaged runtime contains JavaFX;
    // a plain JDK gets it from the module path, and an offscreen run from a patched module.
    List<String> inherited = new ArrayList<>();
    String modulePath = System.getProperty("jdk.module.path");
    if (modulePath != null && !modulePath.isBlank()) {
      List<String> entries = new ArrayList<>();
      for (String entry : modulePath.split(System.getProperty("path.separator")))
        entries.add(Paths.get(entry).toAbsolutePath().normalize().toString());
      inherited.add("--module-path");
      inherited.add(String.join(System.getProperty("path.separator"), entries));
    }
    // Extra JVM options for windows opened from this one (the offscreen test toolkit needs one).
    String extra = System.getProperty("studio.childOptions");
    if (extra != null)
      for (String option : extra.trim().split("\\s+")) if (!option.isEmpty()) inherited.add(option);
    for (String name :
        new String[] {
          "glass.platform",
          "monocle.platform",
          "prism.order",
          "headless.geometry",
          "java.awt.headless",
          "studio.sdk",
          "studio.sdk.refSize"
        })
      if (System.getProperty(name) != null)
        inherited.add("-D" + name + "=" + System.getProperty(name));
    command.addAll(1, inherited);
    // A window opened from this one speaks the same language.
    for (String name : new String[] {"studio.lang", "studio.lang.dir"})
      if (System.getProperty(name) != null)
        command.add(command.indexOf("-jar"), "-D" + name + "=" + System.getProperty(name));
    String readyToken = UUID.randomUUID().toString();
    command.add("--ready-request=" + readyToken);
    command.addAll(arguments);
    return new Launch(
        new ProcessBuilder(command).start().toHandle(),
        directory(id).resolve("ready-" + readyToken));
  }

  public static ProcessHandle running(String id) {
    try {
      Path f = directory(id).resolve("process.pid");
      String[] data = Files.readString(f).trim().split(" ");
      ProcessHandle handle = ProcessHandle.of(Long.parseLong(data[0])).orElse(null);
      if (handle == null
          || !handle.isAlive()
          || !handle
              .info()
              .startInstant()
              .map(i -> Long.toString(i.toEpochMilli()).equals(data[1]))
              .orElse(false)) return null;
      try (var channel =
          java.nio.channels.FileChannel.open(
              directory(id).resolve("session.lock"), StandardOpenOption.WRITE)) {
        try (var lock = channel.tryLock()) {
          if (lock != null) return null;
        }
      } catch (java.nio.channels.OverlappingFileLockException own) {
      }
      return handle;
    } catch (Exception e) {
      return null;
    }
  }

  public static Launch activate(String id, List<String> args) throws IOException {
    ProcessHandle process = running(id);
    if (process == null) throw new IOException(Tr.of("session.alreadyClosed"));
    String connect =
        args.stream()
            .filter(a -> a.startsWith("--connect-request="))
            .map(a -> a.substring(18))
            .findFirst()
            .orElse("");
    String token = WorkspaceRequest.send(directory(id), "activate", connect, process.pid());
    return new Launch(process, directory(id).resolve("ready-" + token));
  }

  public static void requestClose(String id) throws IOException {
    ProcessHandle process = running(id);
    if (process == null) return;
    Path capability = directory(id).resolve("workspace-protocol");
    if (!Files.exists(capability) || !Files.readString(capability).equals(process.pid() + " 2"))
      throw new IOException(Tr.of("session.oldVersion"));
    WorkspaceRequest.send(directory(id), "close", "", process.pid());
  }

  public static void closeHiddenPeers() throws IOException {
    for (Profile p : list())
      if (!p.id().equals(active()) && running(p.id()) != null)
        Files.writeString(directory(p.id()).resolve("close-hidden"), "close");
  }

  public static String encrypt(String id, char[] password) throws Exception {
    byte[] bytes = new String(password).getBytes(StandardCharsets.UTF_8);
    try {
      return crypt(id, Base64.getEncoder().encodeToString(bytes), true);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  public static char[] decrypt(Profile p) throws Exception {
    if (p.password.isEmpty()) return new char[0];
    byte[] bytes = Base64.getDecoder().decode(crypt(p.id, p.password, false));
    try {
      return new String(bytes, StandardCharsets.UTF_8).toCharArray();
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  private static String crypt(String id, String input, boolean protect) throws Exception {
    if (!System.getProperty("os.name").startsWith("Windows"))
      return cryptLocalKey(id, input, protect);
    directory(id);
    if (input.length() > (protect ? 1024 : 4096))
      throw new IOException(Tr.of("session.passwordTooLong"));
    String script =
        "Add-Type -AssemblyName System.Security;"
            + " $b=[Convert]::FromBase64String([Console]::ReadLine());"
            + " $e=[Text.Encoding]::UTF8.GetBytes('SedonaStudio:"
            + id
            + "'); $r=[Security.Cryptography.ProtectedData]::"
            + (protect ? "Protect" : "Unprotect")
            + "($b,$e,[Security.Cryptography.DataProtectionScope]::CurrentUser);"
            + " [Console]::Write([Convert]::ToBase64String($r)); [Array]::Clear($b,0,$b.Length);"
            + " [Array]::Clear($r,0,$r.Length)";
    Path shell =
        Paths.get(
            System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
    Process process =
        new ProcessBuilder(
                shell.toString(),
                "-NoProfile",
                "-NonInteractive",
                "-WindowStyle",
                "Hidden",
                "-Command",
                script)
            .start();
    try (var writer =
        new OutputStreamWriter(process.getOutputStream(), StandardCharsets.US_ASCII)) {
      writer.write(input);
      writer.write('\n');
    }
    if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException(Tr.of("session.dpapiTimeout"));
    }
    if (process.exitValue() != 0) throw new IOException(Tr.of("session.dpapiFailed"));
    return new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).trim();
  }

  /**
   * Non-Windows fallback for DPAPI: AES-256-GCM with a random per-user key stored in a file that
   * only the owner can read (0600). The session id is bound as additional authenticated data.
   */
  private static String cryptLocalKey(String id, String input, boolean protect) throws Exception {
    directory(id);
    if (input.length() > (protect ? 1024 : 4096))
      throw new IOException(Tr.of("session.passwordTooLong"));
    Path keyFile = root().resolve("secret.key");
    byte[] key;
    synchronized (SessionStore.class) {
      if (!Files.exists(keyFile)) {
        Files.createDirectories(keyFile.getParent());
        byte[] fresh = new byte[32];
        new java.security.SecureRandom().nextBytes(fresh);
        try {
          Files.createFile(
              keyFile,
              java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                  java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
          Files.createFile(keyFile);
        }
        Files.write(keyFile, fresh);
      }
      key = Files.readAllBytes(keyFile);
    }
    if (key.length != 32) throw new IOException(Tr.of("session.keyDamaged", keyFile));
    javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
    javax.crypto.spec.SecretKeySpec spec = new javax.crypto.spec.SecretKeySpec(key, "AES");
    byte[] aad = ("SedonaStudio:" + id).getBytes(StandardCharsets.UTF_8);
    try {
      if (protect) {
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            spec,
            new javax.crypto.spec.GCMParameterSpec(128, iv));
        cipher.updateAAD(aad);
        byte[] sealed = cipher.doFinal(Base64.getDecoder().decode(input));
        byte[] out = new byte[iv.length + sealed.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(sealed, 0, out, iv.length, sealed.length);
        return Base64.getEncoder().encodeToString(out);
      }
      byte[] all = Base64.getDecoder().decode(input);
      if (all.length < 28) throw new IOException(Tr.of("session.passwordDamaged"));
      cipher.init(
          javax.crypto.Cipher.DECRYPT_MODE,
          spec,
          new javax.crypto.spec.GCMParameterSpec(128, all, 0, 12));
      cipher.updateAAD(aad);
      byte[] plain = cipher.doFinal(all, 12, all.length - 12);
      try {
        return Base64.getEncoder().encodeToString(plain);
      } finally {
        Arrays.fill(plain, (byte) 0);
      }
    } catch (java.security.GeneralSecurityException e) {
      throw new IOException(Tr.of("session.passwordFailed"), e);
    } finally {
      Arrays.fill(key, (byte) 0);
    }
  }
}

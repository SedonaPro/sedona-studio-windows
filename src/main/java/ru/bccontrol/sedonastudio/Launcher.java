package ru.bccontrol.sedonastudio;

public final class Launcher {
  private static java.nio.channels.FileChannel sessionLockChannel;
  private static java.nio.channels.FileLock sessionLock;

  public static void main(String[] args) throws Exception {
    ru.bccontrol.sedonastudio.storage.Languages.activate(
        ru.bccontrol.sedonastudio.storage.Languages.choose(
            System.getProperty("studio.lang"),
            java.util.prefs.Preferences.userRoot()
                .node("ru/bccontrol/sedona-studio-windows")
                .get("language", null),
            java.util.Locale.getDefault()));
    String local = System.getenv("LOCALAPPDATA");
    String defaultData =
        local != null
            ? java.nio.file.Paths.get(local, "BCControl", "SedonaStudioWindows").toString()
            : java.nio.file.Paths.get(
                    System.getProperty("user.home"), ".local", "share", "sedona-studio")
                .toString();
    String data =
        System.getProperty(
            "studio.data",
            System.getenv("SEDONA_STUDIO_DATA") == null
                ? defaultData
                : System.getenv("SEDONA_STUDIO_DATA"));
    String root = System.getProperty("studio.root", data);
    System.setProperty("studio.root", root);
    String id = System.getProperty("studio.session", "local");
    System.setProperty("studio.session", id);
    java.nio.file.Path session = ru.bccontrol.sedonastudio.storage.SessionStore.directory(id);
    java.nio.file.Files.createDirectories(session);
    sessionLockChannel =
        java.nio.channels.FileChannel.open(
            session.resolve("session.lock"),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE);
    sessionLock = sessionLockChannel.tryLock();
    if (sessionLock == null) {
      ru.bccontrol.sedonastudio.storage.SessionStore.activate(id, java.util.Arrays.asList(args));
      sessionLockChannel.close();
      return;
    }

    java.nio.file.Files.writeString(
        session.resolve("process.pid"),
        ProcessHandle.current().pid()
            + " "
            + ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli());
    java.nio.file.Files.writeString(
        session.resolve("workspace-protocol"), ProcessHandle.current().pid() + " 2");
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    java.nio.file.Files.deleteIfExists(session.resolve("process.pid"));
                    java.nio.file.Files.deleteIfExists(session.resolve("workspace-protocol"));
                  } catch (Exception ignored) {
                  }
                },
                "session-pid-cleanup"));

    data = session.toString();
    System.setProperty("studio.data", data);
    // Sedona's static ManifestDb/Schema caches require a separate JVM for each session.
    System.setProperty("sedona.home", session.resolve("cache").toString());
    if (System.getProperty("sedona.home") == null)
      System.setProperty("sedona.home", java.nio.file.Paths.get(data, "cache").toString());
    java.nio.file.Path lib = java.nio.file.Paths.get(System.getProperty("sedona.home"), "lib");
    java.nio.file.Files.createDirectories(lib);
    java.nio.file.Path properties = lib.resolve("sedona.properties");
    if (!java.nio.file.Files.exists(properties))
      java.nio.file.Files.write(properties, "# Sedona Studio private cache\n".getBytes("UTF-8"));
    javafx.application.Application.launch(ru.bccontrol.sedonastudio.ui.StudioApp.class, args);
  }
}

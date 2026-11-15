package ru.bccontrol.sedonastudio.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.storage.AppFiles;

/**
 * Builds and runs Sedona images with an external SDK. Everything runs as separate processes in a
 * folder the caller chooses; nothing here talks to a device.
 */
public final class Toolchain {
  private static final int COMPILE_SECONDS = 180;
  private final Sdk sdk;

  public Toolchain(Sdk sdk) {
    this.sdk = sdk;
  }

  public Sdk sdk() {
    return sdk;
  }

  /** Runs the Sedona compiler on a kits.xml, app.sax or kit.xml; output goes to the log file. */
  public void compile(Path input, Path log) throws IOException, InterruptedException {
    if (run(input, log) != 0)
      throw new IOException(Tr.of("toolchain.compileFailed", input.getFileName(), tail(log)));
  }

  /** The same, but a failed compilation is an answer (the exit code), not an exception. */
  public int run(Path input, Path log) throws IOException, InterruptedException {
    return run(input, log, new String[0]);
  }

  public int run(Path input, Path log, String... options) throws IOException, InterruptedException {
    String java =
        Paths.get(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();
    String classpath =
        sdk.home.resolve("lib/sedona.jar")
            + System.getProperty("path.separator")
            + sdk.home.resolve("lib/sedonac.jar");
    List<String> command =
        new ArrayList<>(
            List.of(
                java,
                "-Dsedona.home=" + sdk.home,
                "-cp",
                classpath,
                "sedonac.Main",
                input.toAbsolutePath().toString()));
    command.addAll(List.of(options));
    Process process =
        new ProcessBuilder(command)
            .directory(input.toAbsolutePath().getParent().toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    if (!process.waitFor(COMPILE_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException(Tr.of("toolchain.timeout", input.getFileName()));
    }
    return process.exitValue();
  }

  /**
   * Writes kits.xml for exactly these kit parts and compiles kits.scode into the folder. Refuses
   * when the SDK lacks a kit or has only another build of it.
   */
  public Path buildCode(List<String> parts, Path folder) throws IOException, InterruptedException {
    List<String> absent = new ArrayList<>();
    StringBuilder xml =
        new StringBuilder(
            "<sedonaCode endian=\""
                + sdk.endian
                + "\" blockSize=\""
                + sdk.blockSize
                + "\" refSize=\""
                + sdk.refSize
                + "\" main=\"sys::Sys.main\" debug=\"false\" test=\"false\">\n");
    // Kit numbers in the code must match those in app.sab, where Sedona orders the kits itself:
    // "sys" first, the rest by name.
    List<String> ordered = withDependencies(parts);
    ordered.sort(
        java.util.Comparator.comparing((String part) -> !part.startsWith("sys-"))
            .thenComparing(part -> part.substring(0, Math.max(part.lastIndexOf('-'), 0))));
    for (String part : ordered) {
      if (sdk.kitFile(part) == null) absent.add(part);
      int dash = part.lastIndexOf('-');
      xml.append("  <depend on=\"")
          .append(part, 0, Math.max(dash, 0))
          .append(" 0x")
          .append(part.substring(dash + 1))
          .append("\"/>\n");
    }
    if (!absent.isEmpty())
      throw new IOException(Tr.of("toolchain.kitsNotInSdk", String.join(", ", absent), sdk.home));
    Files.createDirectories(folder);
    Path kits = folder.resolve("kits.xml");
    Files.writeString(kits, xml.append("</sedonaCode>\n"), StandardCharsets.UTF_8);
    Files.deleteIfExists(folder.resolve("kits.scode"));
    compile(kits, folder.resolve("kits.log"));
    Path code = folder.resolve("kits.scode");
    if (!Files.isRegularFile(code))
      throw new IOException(
          Tr.of("toolchain.compileFailed", "kits.xml", tail(kits.resolveSibling("kits.log"))));
    return code;
  }

  /**
   * The parts plus the SDK's builds of the kits they depend on. An app names only the kits whose
   * components it uses, but the code must also hold every kit those are compiled against.
   */
  public List<String> withDependencies(List<String> parts) {
    List<String> all = new ArrayList<>(parts);
    java.util.regex.Pattern depend =
        java.util.regex.Pattern.compile("<depend\\s+on=\"([A-Za-z0-9_]+)");
    for (int i = 0; i < all.size() && all.size() < 256; i++) {
      byte[] manifest;
      try {
        manifest = sdk.kitSource().manifest(all.get(i));
      } catch (Exception unreadable) {
        manifest = null;
      }
      if (manifest == null) continue;
      java.util.regex.Matcher m = depend.matcher(new String(manifest, StandardCharsets.UTF_8));
      while (m.find()) {
        String kit = m.group(1);
        if (all.stream().anyMatch(part -> part.startsWith(kit + "-"))) continue;
        String part = sdk.kitPart(kit);
        // A dependency the SDK lacks is reported by the compiler with its own message.
        if (part != null) all.add(part);
      }
    }
    return all;
  }

  /**
   * A complete image of the project in the folder: kits.scode for its exact kits and app.sab.
   * Returns the secret fields that were left at their defaults (see AppFiles.write).
   */
  public List<String> buildImage(Project project, Path folder) throws Exception {
    buildCode(project.parts, folder);
    return AppFiles.write(project, folder.resolve("app.sab"));
  }

  /** Starts the VM on the image in the folder; its output goes to vm.log there. */
  public Vm start(Path folder) throws IOException {
    sdk.requireVm();
    for (String file : new String[] {"kits.scode", "app.sab"})
      if (!Files.isRegularFile(folder.resolve(file)))
        throw new IOException(Tr.of("toolchain.imageIncomplete", file));
    // Platform mode, as on a controller: the VM restarts in place and installs *.stage files.
    ProcessBuilder builder =
        new ProcessBuilder(sdk.executable().toString(), "--plat")
            .directory(folder.toFile())
            .redirectErrorStream(true)
            .redirectOutput(folder.resolve("vm.log").toFile());
    // A VM started from the editor is for this computer only. VMs that know the setting listen
    // on loopback alone; older ones ignore it and listen everywhere.
    builder.environment().put("SVM_INET_BIND", "127.0.0.1");
    Process process = builder.start();
    return new Vm(process, folder);
  }

  static String tail(Path log) {
    try {
      List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
      return String.join("\n", lines.subList(Math.max(0, lines.size() - 12), lines.size()));
    } catch (Exception e) {
      return "";
    }
  }

  /** A running VM process. Always stopped when the editor exits. */
  public static final class Vm implements AutoCloseable {
    private final Process process;
    private final Path folder;
    private final Thread onExit;

    Vm(Process process, Path folder) {
      this.process = process;
      this.folder = folder;
      this.onExit = new Thread(process::destroyForcibly, "sedona-vm-stop");
      Runtime.getRuntime().addShutdownHook(onExit);
    }

    public boolean alive() {
      return process.isAlive();
    }

    public Path folder() {
      return folder;
    }

    /** The last lines the VM printed. */
    public String output() {
      return tail(folder.resolve("vm.log"));
    }

    @Override
    public void close() {
      process.destroy();
      try {
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
      } catch (InterruptedException e) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
      }
      try {
        Runtime.getRuntime().removeShutdownHook(onExit);
      } catch (IllegalStateException shuttingDown) {
        // The hook is already running.
      }
    }
  }
}

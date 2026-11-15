package ru.bccontrol.sedonastudio.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.bccontrol.sedonastudio.model.Tr;

/**
 * Developing a kit with an external SDK: start one from a template, compile it, run its unit tests
 * in the local VM. Sources are edited in any editor; this only drives the SDK and reads what it
 * says. A compiled kit is installed into the SDK's Sedona home, where the compiler puts it.
 */
public final class KitWorkshop {
  /**
   * A compiler message tied to a place in the sources (line and column start at 1, 0 = unknown).
   */
  public record Diagnostic(Path file, int line, int column, String message) {
    @Override
    public String toString() {
      return (file == null ? "" : file.getFileName() + (line > 0 ? ":" + line : "") + "  ")
          + message;
    }
  }

  /**
   * What a build or a test run produced. part is the kit build ("name-checksum") after a successful
   * compilation; asserts counts passed unit-test assertions, failures the failed ones.
   */
  public record Result(
      boolean ok,
      String part,
      List<Diagnostic> diagnostics,
      String log,
      int asserts,
      int failures) {}

  private static final Pattern PLACE =
      Pattern.compile("^\\s*(.+?\\.(?:sedona|xml|c|h)):(\\d+)(?::(\\d+))?:\\s*(.*)$");
  private static final Pattern NAME =
      Pattern.compile("<sedonaKit[^>]*?\\bname\\s*=\\s*\"([^\"]+)\"");
  private static final int TEST_SECONDS = 120;
  private final Toolchain toolchain;

  public KitWorkshop(Toolchain toolchain) {
    this.toolchain = toolchain;
  }

  /** The kit name written in kit.xml of that folder. */
  public static String kitName(Path kitFolder) throws IOException {
    Path xml = kitFolder.resolve("kit.xml");
    if (!Files.isRegularFile(xml)) throw new IOException(Tr.of("kit.noKitXml", kitFolder));
    Matcher m = NAME.matcher(Files.readString(xml, StandardCharsets.UTF_8));
    if (!m.find()) throw new IOException(Tr.of("kit.noKitXml", kitFolder));
    return m.group(1);
  }

  /**
   * Creates parent/name with a kit that compiles and passes its own test as generated: one
   * component (Gain: out = in * gain, limited to lo..hi) and its unit test.
   */
  public static Path create(Path parent, String vendor, String name) throws IOException {
    if (!vendor.matches("[A-Z][A-Za-z0-9]{1,15}"))
      throw new IllegalArgumentException(Tr.of("kit.badVendor"));
    if (!name.matches("[a-z][A-Za-z0-9]{1,30}")
        || !name.toLowerCase(java.util.Locale.ROOT)
            .startsWith(vendor.toLowerCase(java.util.Locale.ROOT))
        || name.length() == vendor.length())
      throw new IllegalArgumentException(
          Tr.of("kit.badName", vendor.toLowerCase(java.util.Locale.ROOT)));
    Path folder = parent.resolve(name);
    if (Files.exists(folder)) throw new IOException(Tr.of("kit.exists", folder));
    Files.createDirectories(folder.resolve("test"));
    Files.writeString(
        folder.resolve("kit.xml"),
        "<sedonaKit\n name = \""
            + name
            + "\"\n vendor = \""
            + vendor
            + "\"\n"
            + " description = \"TODO: one line about the kit\"\n"
            + " includeSource = \"false\"\n"
            + " doc = \"true\"\n"
            + ">\n"
            + "  <depend on=\"sys 1.2+\" />\n\n"
            + "  <source dir=\".\" />\n"
            + "  <source dir=\"test\" testonly=\"true\" />\n"
            + "</sedonaKit>\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        folder.resolve("Gain.sedona"),
        "**\n** Example component: out = in * gain, limited to lo..hi.\n"
            + "** Rename or replace it; every public class of the kit is a palette entry.\n**\n"
            + "class Gain extends Component\n{\n  ** input value\n  property float in\n\n"
            + "  ** multiplier\n  @config property float gain = 1.0f\n\n"
            + "  ** lower limit of out\n  @config property float lo = -1000.0f\n\n"
            + "  ** upper limit of out\n  @config property float hi = 1000.0f\n\n"
            + "  ** in * gain, limited to lo..hi\n  @readonly property float out\n\n"
            + "  override virtual void execute()\n  {\n    out := clamp(in * gain, lo, hi)\n  }\n\n"
            + "  ** limits v to lo..hi\n  static float clamp(float v, float lo, float hi)\n  {\n"
            + "    if (v < lo) return lo\n    if (v > hi) return hi\n    return v\n  }\n}\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        folder.resolve("test/GainTest.sedona"),
        "class GainTest extends Test\n"
            + "{\n"
            + "  static inline Gain g\n\n"
            + "  static void test()\n"
            + "  {\n"
            + "    g.in := 2.0f\n"
            + "    g.gain := 3.0f\n"
            + "    g.execute()\n"
            + "    assert(g.out == 6.0f)\n\n"
            + "    g.hi := 5.0f\n"
            + "    g.execute()\n"
            + "    assert(g.out == 5.0f)\n\n"
            + "    g.in := -2000.0f\n"
            + "    g.gain := 1.0f\n"
            + "    g.execute()\n"
            + "    assert(g.out == -1000.0f)\n"
            + "  }\n"
            + "}\n",
        StandardCharsets.UTF_8);
    return folder;
  }

  /** Compiles the kit; logs go to the work folder. */
  public Result build(Path kitFolder, Path work) throws Exception {
    String name = kitName(kitFolder);
    Files.createDirectories(work);
    Path log = work.resolve(name + "-build.log");
    if (hasNatives(kitFolder)) return buildWithVm(kitFolder, name, log);
    int exit = toolchain.run(kitFolder.resolve("kit.xml"), log);
    String text = read(log);
    String part = exit == 0 ? toolchain.sdk().kitPart(name) : null;
    return new Result(exit == 0 && part != null, part, diagnostics(text, kitFolder), text, 0, 0);
  }

  /** True when the kit declares native methods: its C code must be compiled into the VM. */
  public static boolean hasNatives(Path kitFolder) throws IOException {
    Path xml = kitFolder.resolve("kit.xml");
    return Files.isRegularFile(xml)
        && Pattern.compile("<native\\s")
            .matcher(Files.readString(xml, StandardCharsets.UTF_8))
            .find();
  }

  /**
   * A kit with natives: the SDK's own build tool compiles the kit and rebuilds the VM with its C
   * code. Works only for a kit inside a SedonaPro tree, and it changes that tree's build - the VM
   * every user of the SDK runs.
   */
  private Result buildWithVm(Path kitFolder, String name, Path log) throws Exception {
    if (System.getProperty("os.name").startsWith("Windows"))
      return new WindowsNative(toolchain).build(kitFolder, name, log);
    Path tree = toolchain.sdk().tree;
    if (tree == null || !kitFolder.toAbsolutePath().normalize().startsWith(tree))
      throw new IOException(Tr.of("kit.nativesNeedTree", name));
    int exit = spro(tree, log, "build", "linux", "--add", name);
    String text = read(log);
    String part = exit == 0 ? toolchain.sdk().kitPart(name) : null;
    return new Result(exit == 0 && part != null, part, diagnostics(text, kitFolder), text, 0, 0);
  }

  /**
   * Creates a kit with one C native method inside the SDK tree (kits/domain/name), through the
   * SDK's "spro kit new", which also reserves a native id for it. Returns the kit folder.
   */
  public Path createNative(String domain, String vendor, String name, Path work) throws Exception {
    if (System.getProperty("os.name").startsWith("Windows"))
      return new WindowsNative(toolchain).create(domain, vendor, name);
    Path tree = toolchain.sdk().tree;
    if (tree == null) throw new IOException(Tr.of("kit.nativesNeedTree", name));
    if (!domain.matches("[a-z][a-z0-9-]{0,30}"))
      throw new IllegalArgumentException(Tr.of("kit.badDomain"));
    Files.createDirectories(work);
    Path log = work.resolve(name + "-new.log");
    if (spro(tree, log, "kit", "new", name, "--domain", domain, "--vendor", vendor, "--native")
        != 0) throw new IOException(read(log).strip());
    return tree.resolve("kits").resolve(domain).resolve(name);
  }

  private static int spro(Path tree, Path log, String... arguments)
      throws IOException, InterruptedException {
    List<String> command =
        new ArrayList<>(List.of(Portable.python(), tree.resolve("spro").toString()));
    command.addAll(List.of(arguments));
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .directory(tree.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    // The tool calls "java": the computer's own one first, the editor's as the fallback.
    String path = builder.environment().getOrDefault("PATH", "");
    builder
        .environment()
        .put(
            "PATH",
            path
                + System.getProperty("path.separator")
                + java.nio.file.Paths.get(System.getProperty("java.home"), "bin"));
    Process process = builder.start();
    if (!process.waitFor(600, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException(Tr.of("toolchain.timeout", "spro " + arguments[0]));
    }
    return process.exitValue();
  }

  /** Compiles the kit and runs its unit tests in the local VM. */
  public Result test(Path kitFolder, Path work) throws Exception {
    Result built = build(kitFolder, work);
    if (!built.ok()) return built;
    toolchain.sdk().requireVm();
    String name = kitName(kitFolder);
    Sdk sdk = toolchain.sdk();
    List<String> parts = toolchain.withDependencies(List.of(built.part()));
    parts.sort(
        java.util.Comparator.comparing((String p) -> !p.startsWith("sys-"))
            .thenComparing(p -> p.substring(0, p.lastIndexOf('-'))));
    StringBuilder xml =
        new StringBuilder(
            "<sedonaCode endian=\""
                + sdk.endian
                + "\" blockSize=\""
                + sdk.blockSize
                + "\" refSize=\""
                + sdk.refSize
                + "\" main=\"sys::Sys.main\" debug=\"true\" test=\"true\">\n");
    for (String part : parts) {
      int dash = part.lastIndexOf('-');
      // Only the kit under test brings its tests along.
      xml.append("  <depend on=\"")
          .append(part, 0, dash)
          .append(" 0x")
          .append(part.substring(dash + 1))
          .append("\" test=\"")
          .append(part.equals(built.part()))
          .append("\"/>\n");
    }
    Path image = work.resolve(name + "-tests.xml");
    Files.writeString(image, xml.append("</sedonaCode>\n"), StandardCharsets.UTF_8);
    Path compileLog = work.resolve(name + "-tests-build.log");
    Path code = work.resolve(name + "-tests.scode");
    Files.deleteIfExists(code);
    if (toolchain.run(image, compileLog) != 0 || !Files.isRegularFile(code)) {
      String text = built.log() + "\n" + read(compileLog);
      return new Result(false, built.part(), diagnostics(text, kitFolder), text, 0, 0);
    }
    Path runLog = work.resolve(name + "-tests.log");
    Process process =
        new ProcessBuilder(sdk.executable().toString(), code.getFileName().toString(), "-test")
            .directory(work.toFile())
            .redirectErrorStream(true)
            .redirectOutput(runLog.toFile())
            .start();
    boolean finished = process.waitFor(TEST_SECONDS, TimeUnit.SECONDS);
    if (!finished) process.destroyForcibly();
    String output = read(runLog);
    int asserts = number(output, "Assert Successes\\s*=\\s*(\\d+)"),
        failures = number(output, "Assert Failures\\s*=\\s*(\\d+)");
    boolean ok = finished && process.exitValue() == 0 && asserts > 0 && failures == 0;
    List<Diagnostic> found = new ArrayList<>(diagnostics(output, kitFolder));
    found.addAll(failedAsserts(output, kitFolder));
    if (!finished) found.add(new Diagnostic(null, 0, 0, Tr.of("kit.testsTimedOut")));
    return new Result(ok, built.part(), found, built.log() + "\n" + output, asserts, failures);
  }

  private static final Pattern ASSERT =
      Pattern.compile("ASSERT FAILURE:\\s*\\S+?::(\\w+)\\.(\\w+)\\s*\\[Line (\\d+)\\]");

  /** "ASSERT FAILURE: kit::Class.method [Line n]" as a place in Class.sedona of the kit. */
  private static List<Diagnostic> failedAsserts(String output, Path kitFolder) {
    List<Diagnostic> found = new ArrayList<>();
    Matcher m = ASSERT.matcher(output);
    while (m.find()) {
      String type = m.group(1);
      Path file = null;
      try (var files = Files.walk(kitFolder, 4)) {
        file =
            files
                .filter(p -> p.getFileName().toString().equals(type + ".sedona"))
                .findFirst()
                .orElse(null);
      } catch (IOException e) {
        // The message is still shown, without a place.
      }
      found.add(
          new Diagnostic(
              file, Integer.parseInt(m.group(3)), 0, Tr.of("kit.assertFailed", type, m.group(2))));
    }
    return found;
  }

  private static int number(String text, String pattern) {
    Matcher m = Pattern.compile(pattern).matcher(text);
    return m.find() ? Integer.parseInt(m.group(1)) : -1;
  }

  private static String read(Path log) {
    try {
      return Files.readString(log, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return "";
    }
  }

  private static Path ownFile(Path kitFolder, String name) {
    try (var files = Files.walk(kitFolder, 4)) {
      return files.filter(p -> p.getFileName().toString().equals(name)).findFirst().orElse(null);
    } catch (IOException e) {
      return null;
    }
  }

  /** Lines of the form "file.sedona:line[:column]: message" as places in the kit's sources. */
  static List<Diagnostic> diagnostics(String log, Path kitFolder) {
    List<Diagnostic> found = new ArrayList<>();
    for (String line : log.split("\\R")) {
      Matcher m = PLACE.matcher(line);
      if (!m.matches()) continue;
      Path file = java.nio.file.Paths.get(m.group(1));
      if (!file.isAbsolute()) file = kitFolder.resolve(file);
      file = file.normalize();
      if (!file.startsWith(kitFolder.toAbsolutePath().normalize())) {
        // The C compiler works on staged copies: point at the kit's own file of that name. What
        // is said about other kits' files is kept only when it is an error.
        Path own = ownFile(kitFolder, file.getFileName().toString());
        if (own != null) file = own;
        else if (!m.group(4).startsWith("error")) continue;
      }
      if (m.group(4).startsWith("note:")) continue;
      found.add(
          new Diagnostic(
              file.normalize(),
              Integer.parseInt(m.group(2)),
              m.group(3) == null ? 0 : Integer.parseInt(m.group(3)),
              m.group(4)));
    }
    return found;
  }
}

package ru.bccontrol.sedonastudio.toolchain;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import ru.bccontrol.sedonastudio.model.Tr;

/** Classic 32-bit Sedona ABI, built locally with the portable LLVM-MinGW toolchain. */
public final class WindowsNative {
  private static final String COMPILER = "bin/i686-w64-mingw32-clang.exe";
  private static final String PLATFORM = "platforms/src/generic/win32/generic-win32.xml";
  private final Toolchain toolchain;

  public WindowsNative(Toolchain toolchain) {
    this.toolchain = toolchain;
  }

  public static Path compiler() {
    String override = System.getProperty("studio.cc", "");
    if (!override.isBlank()) return Path.of(override).toAbsolutePath();
    try {
      Path at =
          Path.of(WindowsNative.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      for (; at != null; at = at.getParent()) {
        for (String relative :
            List.of(
                "c-toolchain/" + COMPILER,
                "lib/c-toolchain/llvm-mingw-20240619-ucrt-x86_64/" + COMPILER)) {
          Path candidate = at.resolve(relative);
          if (Files.isRegularFile(candidate)) return candidate;
        }
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  public static boolean available(Sdk sdk) {
    return System.getProperty("os.name").startsWith("Windows")
        && sdk.refSize == 4
        && Files.isRegularFile(sdk.home.resolve(PLATFORM))
        && compiler() != null
        && Files.isRegularFile(compiler());
  }

  private void require() throws IOException {
    if (!available(toolchain.sdk())) throw new IOException(Tr.of("kit.windowsCompilerMissing"));
  }

  public Path create(String domain, String vendor, String name) throws Exception {
    require();
    if (!domain.matches("[a-z][a-z0-9-]{0,30}"))
      throw new IllegalArgumentException(Tr.of("kit.badDomain"));
    Path home = toolchain.sdk().home;
    try (FileChannel channel =
            FileChannel.open(
                home.resolve("native-build.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock lock = channel.lock()) {
      Set<Integer> used = new HashSet<>();
      Pattern ids = Pattern.compile("id\\s*=\\s*\"(\\d+)::\\d+\"");
      try (var files = Files.walk(home.resolve("src"))) {
        for (Path xml : files.filter(p -> p.getFileName().toString().equals("kit.xml")).toList()) {
          Matcher matcher = ids.matcher(Files.readString(xml));
          while (matcher.find()) used.add(Integer.parseInt(matcher.group(1)));
        }
      }
      int id = 16;
      while (used.contains(id) && id < 255) id++;
      if (used.contains(id)) throw new IOException(Tr.of("kit.nativeIdsFull"));
      Path folder =
          KitWorkshop.create(home.resolve("src/studio-native").resolve(domain), vendor, name);
      Path xml = folder.resolve("kit.xml");
      Files.writeString(
          xml,
          Files.readString(xml)
              .replace(
                  "</sedonaKit>",
                  "  <native qname=\""
                      + name
                      + "::Gain.clamp\" id=\""
                      + id
                      + "::0\"/>\n</sedonaKit>"));
      Path source = folder.resolve("Gain.sedona");
      String text = Files.readString(source);
      int start = text.indexOf("  static float clamp");
      Files.writeString(
          source,
          text.substring(0, start)
              + "  native static float clamp(float v, float lo, float hi)\n}\n");
      Files.createDirectories(folder.resolve("native"));
      Files.writeString(
          folder.resolve("native/" + name + "_Gain.c"),
          "#include \"sedona.h\"\n\nCell "
              + name
              + "_Gain_clamp(SedonaVM* vm, Cell* params) {\n"
              + "  float v = params[0].fval, lo = params[1].fval, hi = params[2].fval;\n"
              + "  Cell result;\n"
              + "  result.fval = v < lo ? lo : v > hi ? hi : v;\n"
              + "  return result;\n"
              + "}\n");
      return folder;
    }
  }

  public KitWorkshop.Result build(Path folder, String name, Path log) throws Exception {
    require();
    Sdk sdk = toolchain.sdk();
    Path home = sdk.home.toRealPath();
    folder = folder.toRealPath();
    if (!name.matches("[a-z][A-Za-z0-9]{1,30}")
        || !folder.getFileName().toString().equals(name)
        || !folder.startsWith(home.resolve("src/studio-native")))
      throw new IOException(Tr.of("kit.windowsNativeLocation", home.resolve("src/studio-native")));
    try (FileChannel channel =
            FileChannel.open(
                home.resolve("native-build.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock lock = channel.lock()) {
      Map<Path, byte[]> previous = new LinkedHashMap<>();
      Map<Path, java.nio.file.attribute.FileTime> timestamps = new LinkedHashMap<>();
      List<Path> outputs =
          List.of(home.resolve("kits").resolve(name), home.resolve("manifests").resolve(name));
      for (Path dir : outputs)
        if (Files.exists(dir))
          try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
              previous.put(file, Files.readAllBytes(file));
              timestamps.put(file, Files.getLastModifiedTime(file));
            }
          }
      boolean published = false;
      try {
        int exit = toolchain.run(folder.resolve("kit.xml"), log);
        String text = Files.readString(log);
        if (exit != 0) return result(false, null, text, folder);
        String part = sdk.kitPart(name);
        if (part == null) return result(false, null, text, folder);
        Path work = Files.createTempDirectory(log.getParent(), "native-");
        Path stage = work.resolve("stage");
        StringBuilder additions = new StringBuilder();
        try (var files = Files.walk(home.resolve("src/studio-native"))) {
          for (Path xml :
              files.filter(p -> p.getFileName().toString().equals("kit.xml")).sorted().toList()) {
            String other = KitWorkshop.kitName(xml.getParent());
            String build = sdk.kitPart(other);
            if (build == null || !KitWorkshop.hasNatives(xml.getParent())) continue;
            int dash = build.lastIndexOf('-');
            additions
                .append("    <nativeKit depend=\"")
                .append(other)
                .append(" 0x")
                .append(build.substring(dash + 1))
                .append("\"/>\n");
            String relative =
                home.relativize(xml.getParent().resolve("native")).toString().replace('\\', '/');
            additions.append("    <nativeSource path=\"/").append(relative).append("\"/>\n");
          }
        }
        Path platform = work.resolve("platform.xml");
        Files.writeString(
            platform,
            Files.readString(home.resolve(PLATFORM))
                .replace("</compile>", additions + "</compile>"));
        Path stageLog = work.resolve("platform.log");
        exit = toolchain.run(platform, stageLog, "-outDir", stage.toString());
        text += "\n" + Files.readString(stageLog);
        if (exit != 0) return result(false, null, text, folder);
        Path executable = work.resolve("svm.exe");
        Path buildHeader = work.resolve("studio-build.h");
        Files.writeString(buildHeader, "#define PLAT_BUILD_VERSION \"studio\"\n");
        List<String> command =
            new ArrayList<>(
                List.of(
                    compiler().toString(),
                    "-fms-extensions",
                    "-std=gnu99",
                    "-include",
                    "stdlib.h",
                    "-include",
                    "direct.h",
                    "-include",
                    "float.h",
                    "-include",
                    "math.h",
                    "-O1",
                    "-DWIN32",
                    "-D_WIN32",
                    "-DSOCKET_FAMILY_INET",
                    "-include",
                    buildHeader.toString(),
                    "-I",
                    stage.toString()));
        try (var files = Files.list(stage)) {
          files
              .filter(p -> p.toString().endsWith(".c"))
              .sorted()
              .forEach(p -> command.add(p.toString()));
        }
        command.addAll(
            List.of("-static", "-lws2_32", "-luuid", "-lkernel32", "-o", executable.toString()));
        Path ccLog = work.resolve("c-build.log");
        Process process =
            new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ccLog.toFile())
                .start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
          process.descendants().forEach(ProcessHandle::destroyForcibly);
          process.destroyForcibly();
          throw new IOException(Tr.of("toolchain.timeout", "C"));
        }
        text += "\n" + Files.readString(ccLog);
        Files.writeString(log, text);
        if (process.exitValue() != 0 || !Files.isRegularFile(executable))
          return result(false, null, text, folder);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(executable));
        String file = "svm-native-" + HexFormat.of().formatHex(digest).substring(0, 16) + ".exe";
        Path target = home.resolve("bin").resolve(file);
        if (!Files.exists(target)) Files.copy(executable, target);
        Path pointer = Files.createTempFile(home, "native-vm-", ".tmp");
        Files.writeString(pointer, file + "\n");
        try {
          Files.move(
              pointer,
              home.resolve("native-vm.txt"),
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.ATOMIC_MOVE);
        } finally {
          Files.deleteIfExists(pointer);
        }
        published = true;
        return result(true, part, text, folder);
      } finally {
        if (!published) {
          for (Path dir : outputs)
            if (Files.exists(dir))
              try (var files = Files.walk(dir)) {
                for (Path file : files.filter(Files::isRegularFile).toList())
                  if (!previous.containsKey(file)) Files.delete(file);
              }
          for (var entry : previous.entrySet()) {
            Files.createDirectories(entry.getKey().getParent());
            Files.write(entry.getKey(), entry.getValue());
            Files.setLastModifiedTime(entry.getKey(), timestamps.get(entry.getKey()));
          }
        }
      }
    }
  }

  private static KitWorkshop.Result result(boolean ok, String part, String text, Path folder) {
    return new KitWorkshop.Result(ok, part, KitWorkshop.diagnostics(text, folder), text, 0, 0);
  }
}

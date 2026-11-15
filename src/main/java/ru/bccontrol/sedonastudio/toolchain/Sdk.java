package ru.bccontrol.sedonastudio.toolchain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.bccontrol.sedonastudio.model.Tr;

/**
 * A Sedona SDK on this machine: the compiler, the kits and a VM that runs here. The editor works
 * without one; only building images and running the simulator need it.
 *
 * <p>Two layouts are understood: a SedonaPro checkout that has been built ({@code build/home} and
 * {@code build/<target>-sim/run/svm}) and a classic Sedona home ({@code lib/sedonac.jar}, {@code
 * kits/}, {@code bin/svm}).
 */
public final class Sdk {
  /** Sedona home: lib/sedonac.jar, lib/sedona.jar, kits/. */
  public final Path home;
  /**
   * The source tree the SDK was built from, when it is a SedonaPro checkout (it has the "spro" tool
   * that can rebuild the VM); null for a plain Sedona home.
   */
  public final Path tree;
  /** The VM executable for this machine, or null when the SDK has none. */
  public final Path vm;
  /** Image parameters the VM was built for. */
  public final String endian;

  public final int blockSize, refSize;
  /** Platform id of the VM (as a device reports it), or null when the SDK does not say. */
  public final String platformId;

  private Sdk(
      Path home, Path tree, Path vm, String endian, int blockSize, int refSize, String platformId) {
    this.home = home;
    this.tree = tree;
    this.vm = vm;
    this.endian = endian;
    this.blockSize = blockSize;
    this.refSize = refSize;
    this.platformId = platformId;
  }

  /**
   * The SDK to use: -Dstudio.sdk, then SEDONA_STUDIO_SDK, then the given saved path, then a
   * "sedonapro" folder beside the editor's own folder. Empty when none of them is a usable SDK.
   */
  public static Optional<Sdk> find(String saved, Path editorFolder) {
    List<Path> candidates = new ArrayList<>();
    for (String value :
        new String[] {System.getProperty("studio.sdk"), System.getenv("SEDONA_STUDIO_SDK"), saved})
      if (value != null && !value.isBlank()) candidates.add(Paths.get(value));
    for (Path at = editorFolder; at != null; at = at.getParent()) {
      if (Files.isDirectory(at)) candidates.add(at.resolve("sdk"));
      candidates.add(at.resolveSibling("sedonapro"));
    }
    for (Path candidate : candidates) {
      Optional<Sdk> sdk = at(candidate);
      if (sdk.isPresent()) return sdk;
    }
    return Optional.empty();
  }

  /** The same, looking beside the folder this application runs from. */
  public static Optional<Sdk> find(String saved) {
    Path here;
    try {
      here = Paths.get(Sdk.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (Exception e) {
      here = Paths.get("");
    }
    return find(saved, here.toAbsolutePath());
  }

  /** The SDK at this folder, if it is one. */
  public static Optional<Sdk> at(Path root) {
    if (root == null || !Files.isDirectory(root)) return Optional.empty();
    Path home =
        Files.isDirectory(root.resolve("build/home/lib")) ? root.resolve("build/home") : root;
    if (!Files.isRegularFile(home.resolve("lib/sedonac.jar"))
        || !Files.isRegularFile(home.resolve("lib/sedona.jar"))
        || !Files.isDirectory(home.resolve("kits"))) return Optional.empty();
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    Path vm = null, image = null;
    if (home != root) {
      // SedonaPro: the simulator build for this machine's architecture.
      String arch = System.getProperty("os.arch").contains("64") ? "amd64" : "i386";
      for (String target : new String[] {"linux-" + arch + "-full-sim", "linux-i386-full-sim"}) {
        Path run = root.resolve("build").resolve(target).resolve("run/svm");
        if (!windows && Files.isExecutable(run)) {
          vm = run;
          image = root.resolve("build").resolve(target).resolve("kits.xml");
          break;
        }
      }
    } else {
      // A classic Sedona home: the VM in bin/, or built into build/<arch>/ beside its kits.xml.
      String arch = System.getProperty("os.arch").contains("64") ? "amd64" : "i386";
      Path built = home.resolve("build").resolve(arch).resolve(windows ? "svm.exe" : "svm");
      Path classic = home.resolve(windows ? "bin/svm.exe" : "bin/svm");
      if (Files.isRegularFile(built)) {
        vm = built;
        image = built.resolveSibling("kits.xml");
      } else if (Files.isRegularFile(classic)) vm = classic;
    }
    // The platform manifest lies beside kits.xml under one name or another.
    String platformId = null;
    if (image != null && Files.isDirectory(image.getParent()))
      try (var files = Files.newDirectoryStream(image.getParent(), "*.xml")) {
        for (Path file : files) {
          if (Files.size(file) > 1024 * 1024) continue;
          Matcher m =
              Pattern.compile("<sedonaPlatform[^>]*\\bid=\"([^\"]*)\"")
                  .matcher(Files.readString(file));
          if (m.find()) {
            platformId = m.group(1);
            break;
          }
        }
      } catch (Exception unreadable) {
        // Unknown platform: code for a device cannot be built from this SDK.
      }
    String endian = "little";
    int blockSize = 4, refSize = 4;
    if (image != null && Files.isRegularFile(image))
      try {
        String head = Files.readString(image);
        endian = attribute(head, "endian", endian);
        blockSize = Integer.parseInt(attribute(head, "blockSize", "4"));
        refSize = Integer.parseInt(attribute(head, "refSize", "4"));
      } catch (Exception unreadable) {
        // Keep the classic 32-bit defaults.
      }
    return Optional.of(
        new Sdk(
            home.toAbsolutePath().normalize(),
            home != root && Files.isRegularFile(root.resolve("spro"))
                ? root.toAbsolutePath().normalize()
                : null,
            vm == null ? null : vm.toAbsolutePath().normalize(),
            endian,
            Integer.getInteger("studio.sdk.blockSize", blockSize),
            Integer.getInteger("studio.sdk.refSize", refSize),
            System.getProperty("studio.sdk.platformId", platformId)));
  }

  private static String attribute(String xml, String name, String fallback) {
    Matcher m = Pattern.compile("<sedonaCode[^>]*\\b" + name + "=\"([^\"]*)\"").matcher(xml);
    return m.find() ? m.group(1) : fallback;
  }

  private static final Pattern KIT_FILE = Pattern.compile("^(.+)-([0-9a-fA-F]{1,8})-[^-]+\\.kit$");

  /** The kit file of exactly this part ("name-checksum") in the SDK, or null. */
  public Path kitFile(String part) {
    int dash = part.lastIndexOf('-');
    if (dash < 1) return null;
    String kit = part.substring(0, dash);
    long checksum;
    try {
      checksum = Long.parseLong(part.substring(dash + 1), 16);
    } catch (NumberFormatException e) {
      return null;
    }
    Path dir = home.resolve("kits").resolve(kit);
    if (!Files.isDirectory(dir)) return null;
    try (var files = Files.newDirectoryStream(dir, kit + "-*.kit")) {
      for (Path file : files) {
        // Kit file names do not pad the checksum with zeros; compare the numbers.
        Matcher m = KIT_FILE.matcher(file.getFileName().toString());
        if (m.matches() && m.group(1).equals(kit) && Long.parseLong(m.group(2), 16) == checksum)
          return file;
      }
    } catch (java.io.IOException e) {
      return null;
    }
    return null;
  }

  /** The SDK's build of a kit as "name-checksum" (the newest file if it has several), or null. */
  public String kitPart(String kit) {
    Path dir = home.resolve("kits").resolve(kit);
    if (!Files.isDirectory(dir)) return null;
    Path newest = null;
    try (var files = Files.newDirectoryStream(dir, kit + "-*.kit")) {
      for (Path file : files)
        if (KIT_FILE.matcher(file.getFileName().toString()).matches()
            && (newest == null
                || Files.getLastModifiedTime(file).compareTo(Files.getLastModifiedTime(newest))
                    > 0)) newest = file;
    } catch (java.io.IOException e) {
      return null;
    }
    if (newest == null) return null;
    Matcher m = KIT_FILE.matcher(newest.getFileName().toString());
    m.matches();
    return kit + "-" + String.format("%08x", Long.parseLong(m.group(2), 16));
  }

  /** This SDK as a source of kit manifests for reading app files. */
  public ru.bccontrol.sedonastudio.storage.AppFiles.KitSource kitSource() {
    return new ru.bccontrol.sedonastudio.storage.AppFiles.KitSource() {
      public String part(String kit) {
        return kitPart(kit);
      }

      public byte[] manifest(String part) throws Exception {
        int dash = part.lastIndexOf('-');
        if (dash < 1) return null;
        Path file =
            home.resolve("manifests").resolve(part.substring(0, dash)).resolve(part + ".xml");
        return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
      }
    };
  }

  public Path executable() {
    Path pointer = home.resolve("native-vm.txt");
    try {
      String file = Files.readString(pointer).strip();
      if (file.matches("svm-native-[0-9a-f]{16}\\.exe")) {
        Path candidate = home.resolve("bin").resolve(file);
        if (Files.isRegularFile(candidate)) return candidate;
      }
    } catch (Exception ignored) {
    }
    return vm;
  }

  public void requireVm() {
    if (executable() == null) throw new IllegalStateException(Tr.of("toolchain.noVm", home));
  }

  @Override
  public String toString() {
    return home + (vm == null ? "" : " + " + vm.getFileName());
  }
}

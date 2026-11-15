package ru.bccontrol.sedonastudio.toolchain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Tools the editor brings along so that it does not depend on what the computer has installed. */
public final class Portable {
  private Portable() {}

  /**
   * The Python that runs the SDK's build tool: -Dstudio.python, then the one shipped with the
   * editor ("python" beside the application, or lib/python-linux/python in a development tree),
   * then whatever "python3" the computer has.
   */
  public static String python() {
    String chosen = System.getProperty("studio.python");
    if (chosen != null && !chosen.isBlank()) return chosen;
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    String executable = windows ? "python.exe" : "bin/python3";
    try {
      Path code =
          Paths.get(Portable.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      Path app = Files.isDirectory(code) ? code : code.getParent();
      for (Path home :
          new Path[] {
            app.resolve("python"),
            app.resolve("../../lib/python-linux/python"),
            app.resolve("../../lib/python-windows/python")
          }) {
        Path python = home.resolve(executable).normalize();
        if (Files.isRegularFile(python)) return python.toString();
      }
    } catch (Exception unknownLocation) {
      // Fall through to the computer's own Python.
    }
    return windows ? "python" : "python3";
  }

  /** True when python() is the one shipped with the editor. */
  public static boolean bundledPython() {
    return Paths.get(python()).isAbsolute() && System.getProperty("studio.python") == null;
  }
}

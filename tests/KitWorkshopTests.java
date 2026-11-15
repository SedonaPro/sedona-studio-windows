import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.toolchain.*;

/** Kit development: template, compile, unit tests in the VM. Works on a private view of the SDK so the real one stays clean. */
public final class KitWorkshopTests {
  static int passed;
  static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); passed++; }

  public static void main(String[] args) throws Exception {
    try { run(args); } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
    System.exit(0);
  }

  static void run(String[] args) throws Exception {
    Path out = Paths.get(args[0]);
    Sdk real = Sdk.at(Paths.get(args[1])).orElseThrow(() -> new AssertionError("SDK not recognised"));
    // The compiler installs kits into its Sedona home: give it a throw-away home made of links.
    Path home = out.resolve("sdk");
    Files.createDirectories(home.resolve("kits")); Files.createDirectories(home.resolve("manifests")); Files.createDirectories(home.resolve("bin"));
    if (System.getProperty("os.name").startsWith("Windows")) {
      for (String dir : new String[] {"lib", "kits", "manifests"}) {
        Path source = real.home.resolve(dir);
        try (var files = Files.walk(source)) {
          for (Path file : files.toList()) {
            Path target = home.resolve(dir).resolve(source.relativize(file));
            if (Files.isDirectory(file)) Files.createDirectories(target);
            else Files.copy(file, target);
          }
        }
      }
      Files.copy(real.vm, home.resolve("bin/svm.exe"));
    } else {
      Files.createSymbolicLink(home.resolve("lib"), real.home.resolve("lib"));
      for (String dir : new String[] {"kits", "manifests"})
        try (var kits = Files.newDirectoryStream(real.home.resolve(dir))) { for (Path kit : kits) Files.createSymbolicLink(home.resolve(dir).resolve(kit.getFileName()), kit); }
      Files.createSymbolicLink(home.resolve("bin/svm"), real.vm);
    }
    System.setProperty("studio.sdk.refSize", Integer.toString(real.refSize));
    Sdk sdk = Sdk.at(home).orElseThrow(() -> new AssertionError("Linked SDK not recognised"));
    KitWorkshop workshop = new KitWorkshop(new Toolchain(sdk));
    Path sources = out.resolve("src"), work = out.resolve("work");
    for (String[] bad : new String[][] {{"acme", "acmeBoiler"}, {"Acme", "boiler"}, {"Acme", "acme"}, {"Acme", "AcmeBoiler"}})
      try { KitWorkshop.create(sources, bad[0], bad[1]); throw new AssertionError("Accepted " + bad[0] + "/" + bad[1]); }
      catch (IllegalArgumentException expected) { passed++; }
    Path kit = KitWorkshop.create(sources, "Acme", "acmeTry");
    check(KitWorkshop.kitName(kit).equals("acmeTry"), "Kit name read from kit.xml");
    try { KitWorkshop.create(sources, "Acme", "acmeTry"); throw new AssertionError("Overwrote a kit"); }
    catch (java.io.IOException expected) { passed++; }
    KitWorkshop.Result good = workshop.test(kit, work);
    check(good.ok() && good.part().startsWith("acmeTry-") && good.asserts() == 3 && good.failures() == 0 && good.diagnostics().isEmpty(), "The generated kit compiles and passes its test: " + good.log());
    check(sdk.kitFile(good.part()) != null && real.kitFile(good.part()) == null, "The kit is installed into the Sedona home it was compiled with, not into the real SDK");
    String source = Files.readString(kit.resolve("Gain.sedona"));
    Files.writeString(kit.resolve("Gain.sedona"), source.replace("clamp(in * gain, lo, hi)", "clamp(in * gian, lo, hi)"));
    KitWorkshop.Result broken = workshop.build(kit, work);
    check(!broken.ok() && broken.part() == null && broken.diagnostics().size() == 1, "A compile error is an answer, not an exception");
    KitWorkshop.Diagnostic error = broken.diagnostics().get(0);
    check(error.file().equals(kit.resolve("Gain.sedona")) && error.line() == 24 && error.column() > 0 && error.message().contains("gian"), "The error points at the file, line and column: " + error + " line " + error.line());
    Files.writeString(kit.resolve("Gain.sedona"), source);
    Path test = kit.resolve("test/GainTest.sedona");
    Files.writeString(test, Files.readString(test).replace("assert(g.out == 6.0f)", "assert(g.out == 7.0f)"));
    KitWorkshop.Result failing = workshop.test(kit, work);
    check(!failing.ok() && failing.asserts() == 2 && failing.failures() == 1, "A failing assertion fails the run");
    check(failing.diagnostics().size() == 1 && failing.diagnostics().get(0).file().equals(test) && failing.diagnostics().get(0).line() == 10, "The failed assertion points at the test source: " + failing.diagnostics());
    System.out.println("PASS kit workshop " + passed + " checks: name rules, template compiles and passes, compile error with file/line/column, failing assertion located, real SDK untouched");
  }
}

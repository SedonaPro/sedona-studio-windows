import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.toolchain.*;

/** A kit with C natives: created by the SDK's tool, built into the VM, tested. Works on a copy of the SDK tree. */
public final class KitNativeTests {
  static int passed;
  static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); passed++; }

  public static void main(String[] args) throws Exception {
    try { run(args); } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
    System.exit(0);
  }

  static void run(String[] args) throws Exception {
    Path tree = Paths.get(args[0]), work = Paths.get(args[1]);
    System.out.println("python for the SDK tool: " + Portable.python() + (Portable.bundledPython() ? " (shipped with the editor)" : " (from the computer)"));
    System.setProperty("studio.python", "/chosen/python");
    check(Portable.python().equals("/chosen/python") && !Portable.bundledPython(), "An explicit Python wins");
    System.clearProperty("studio.python");
    Sdk sdk = Sdk.at(tree).orElseThrow(() -> new AssertionError("SDK tree not recognised"));
    check(sdk.tree != null && sdk.tree.equals(tree.toAbsolutePath().normalize()), "The SDK knows its source tree");
    KitWorkshop workshop = new KitWorkshop(new Toolchain(sdk));
    long vmBefore = Files.getLastModifiedTime(sdk.vm).toMillis();
    Path kit = workshop.createNative("logic", "Acme", "acmeNat", work);
    check(kit.equals(sdk.tree.resolve("kits/logic/acmeNat")) && KitWorkshop.hasNatives(kit), "Native kit created inside the tree");
    check(Files.readString(sdk.tree.resolve("registry/native-ids.md")).contains("acmeNat"), "A native id was reserved in the registry");
    try { workshop.createNative("logic", "Acme", "acmeNat", work); throw new AssertionError("Created the same kit twice"); }
    catch (java.io.IOException expected) { check(expected.getMessage().contains("acmeNat"), "The tool's refusal is passed on"); }
    KitWorkshop.Result good = workshop.test(kit, work);
    check(good.ok() && good.asserts() == 3 && good.failures() == 0, "The native kit builds into the VM and passes its test: " + good.log().lines().skip(Math.max(0, good.log().lines().count() - 15)).reduce("", (a, b) -> a + "\n" + b));
    check(Files.getLastModifiedTime(sdk.vm).toMillis() > vmBefore, "The VM was rebuilt");
    Path c = kit.resolve("native/acmeNat_Gain.c");
    String source = Files.readString(c);
    Files.writeString(c, source.replace("r.fval = v < lo", "r.fval = vv < lo"));
    KitWorkshop.Result broken = workshop.build(kit, work);
    check(!broken.ok() && !broken.diagnostics().isEmpty(), "A C error fails the build");
    KitWorkshop.Diagnostic error = broken.diagnostics().get(0);
    check(error.file().equals(c) && error.line() > 0 && error.message().contains("vv"), "The C error points at the kit's own source: " + broken.diagnostics());
    check(broken.diagnostics().stream().allMatch(d -> d.file().startsWith(kit) || d.message().startsWith("error")), "Warnings about other kits' files are left out: " + broken.diagnostics());
    Files.writeString(c, source);
    // A native kit outside an SDK tree cannot be built into a VM.
    Path outside = work.resolve("outside/acmeOut");
    Files.createDirectories(outside);
    Files.writeString(outside.resolve("kit.xml"), Files.readString(kit.resolve("kit.xml")).replace("acmeNat", "acmeOut"));
    try { workshop.build(outside, work); throw new AssertionError("Built a native kit outside the tree"); }
    catch (java.io.IOException expected) { passed++; }
    System.out.println("PASS native kit " + passed + " checks: created by the SDK tool with a native id, VM rebuilt, unit test with the C method passes, C error located in the kit source, refusal outside the tree");
  }
}

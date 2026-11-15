import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.toolchain.*;

/** Real local C compile/test, in a disposable SDK; never contacts a device. */
public final class WindowsNativeTests {
  static int checks;
  static void check(boolean ok, String text) { if (!ok) throw new AssertionError(text); checks++; }
  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[0]).toAbsolutePath(), original = Path.of(args[1]).toAbsolutePath();
    Path home = out.resolve("sdk");
    try (var files = Files.walk(original)) {
      for (Path source : files.toList()) {
        Path target = home.resolve(original.relativize(source));
        if (Files.isDirectory(source)) Files.createDirectories(target); else Files.copy(source, target);
      }
    }
    Sdk sdk = Sdk.at(home).orElseThrow();
    check(WindowsNative.available(sdk), "portable compiler detected");
    KitWorkshop workshop = new KitWorkshop(new Toolchain(sdk));
    Path kit = workshop.createNative("tests", "Acme", "acmeNative", out.resolve("work"));
    check(KitWorkshop.hasNatives(kit), "native declaration generated");
    KitWorkshop.Result good = workshop.test(kit, out.resolve("work"));
    check(good.ok() && good.asserts() == 3 && good.failures() == 0, "native template compiles and executes: " + good.log());
    var kitTime = Files.getLastModifiedTime(sdk.kitFile(good.part()));
    Path vm = sdk.executable(); byte[] vmBytes = Files.readAllBytes(vm);
    String pointer = Files.readString(home.resolve("native-vm.txt"));
    check(!vm.equals(sdk.vm), "new runs use published versioned VM");
    Path c = kit.resolve("native/acmeNative_Gain.c"); String text = Files.readString(c);
    Files.writeString(c, text.replace("result.fval =", "result.fval = this_is_a_compile_error +"));
    KitWorkshop.Result broken = workshop.build(kit, out.resolve("work"));
    check(!broken.ok() && broken.diagnostics().stream().anyMatch(d -> c.equals(d.file()) && d.line() > 0), "C error maps to editor source: " + broken.diagnostics());
    check(pointer.equals(Files.readString(home.resolve("native-vm.txt"))) && Arrays.equals(vmBytes, Files.readAllBytes(sdk.executable())) && good.part().equals(sdk.kitPart("acmeNative")) && kitTime.equals(Files.getLastModifiedTime(sdk.kitFile(good.part()))), "failed C preserves prior VM and kit");
    Files.writeString(c, text);
    Path second = workshop.createNative("tests", "Acme", "acmeSecond", out.resolve("work2"));
    KitWorkshop.Result another = workshop.test(second, out.resolve("work2"));
    check(another.ok() && another.asserts() == 3, "second native kit executes: " + another.log());
    KitWorkshop.Result again = workshop.test(kit, out.resolve("work3"));
    check(again.ok() && again.asserts() == 3, "both native tables coexist: " + again.log());
    check(!Files.exists(original.resolve("native-vm.txt")), "source SDK untouched");
    System.out.println("PASS Windows native workshop " + checks + " checks: local C compilation, VM publish, assertions, mapped C diagnostic, rollback, multiple kits, original untouched");
  }
}

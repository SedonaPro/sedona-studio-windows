import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.protocol.SoxDeviceSession;
import ru.bccontrol.sedonastudio.storage.AppFiles;
import ru.bccontrol.sedonastudio.storage.SchemaCatalog;
import ru.bccontrol.sedonastudio.toolchain.*;

/** Uploading a whole application (and code) to a device; the device is the local VM on loopback 13987. */
public final class DeployTests {
  static int passed;
  static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); passed++; }
  static Project.Node named(Project p, String name) {
    for (Project.Node n : p.nodes.values()) if (n.name.equals(name)) return n;
    return null;
  }
  static Project.Node type(List<String> parts, String qname) throws Exception {
    sedona.KitPart[] kits = new sedona.KitPart[parts.size()];
    for (int i = 0; i < kits.length; i++) kits[i] = sedona.KitPart.parse(parts.get(i));
    for (Project.Node n : SchemaCatalog.types(sedona.Schema.load(kits))) if (n.type.equals(qname)) return n;
    throw new AssertionError("No type " + qname);
  }

  public static void main(String[] args) throws Exception {
    try { run(args); } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
    System.exit(0);
  }

  static void run(String[] args) throws Exception {
    Path out = Paths.get(args[0]);
    Sdk sdk = Sdk.at(Paths.get(args[1])).orElseThrow(() -> new AssertionError("SDK not recognised"));
    Toolchain toolchain = new Toolchain(sdk);
    List<String> base = new ArrayList<>(List.of(sdk.kitPart("sys"), sdk.kitPart("types"), sdk.kitPart("math")));
    Project bare = new Project();
    Project.Node source = type(base, "types::ConstFloat"), add = type(base, "math::Add2");
    source.id = "a"; source.name = "src1"; source.x = 120; source.y = 120; source.values.put("out", "14.5");
    add.id = "b"; add.name = "sum1"; add.x = 480; add.y = 120;
    bare.nodes.put("a", source); bare.nodes.put("b", add);
    bare.parts.addAll(List.of(source.kitPart, add.kitPart));
    bare.connect("a", "out", "b", "in1");
    Path device = out.resolve("device");
    SimImage.Access access = SimImage.build(toolchain, bare, device, 13987);
    try (Toolchain.Vm vm = toolchain.start(device)) {
      SoxDeviceSession session = new SoxDeviceSession();
      Project before = null;
      for (int attempt = 0; attempt < 60 && before == null; attempt++) {
        if (!vm.alive()) throw new AssertionError("Device VM exited:\n" + vm.output());
        try { before = session.connect("127.0.0.1", 13987, access.user(), access.password().clone()); } catch (Exception notYet) { Thread.sleep(250); }
      }
      if (before == null) throw new AssertionError("No connection to the device VM\n" + vm.output());
      byte[] deviceApp = session.fetch("app.sab"), deviceCode = session.fetch("kits.scode");
      // A changed application: a value, a new block, a new link.
      Project draft = before.copy();
      named(draft, "src1").values.put("out", "20.5");
      Project.Node more = type(before.parts, "math::Add2");
      more.id = "draft-new"; more.name = "sum2"; more.x = 480; more.y = 360;
      draft.nodes.put(more.id, more);
      draft.connect(named(draft, "src1").id, "out", "draft-new", "in2");
      byte[] app = AppFiles.forDevice(draft, before, deviceApp);
      check(!new String(app, java.nio.charset.StandardCharsets.ISO_8859_1).contains(new String(access.password())), "The upload carries the device's credentials as the device stores them, never a password");
      Project after = session.deploy(app, null, out.resolve("op1"), access.password().clone());
      check(named(after, "sum2") != null && named(after, "src1").values.get("out").equals("20.5"), "The device runs the uploaded application");
      check(after.wires.stream().anyMatch(w -> w.target.equals(named(after, "sum2").id) && w.targetPort.equals("in2")), "With its new link");
      check(session.connected() && vm.alive(), "Logged in again with the same password: the user survived the upload");
      Thread.sleep(700);
      check(session.sample(named(after, "sum2").id).get("out").startsWith("20.5"), "The uploaded application is executing");
      check(Arrays.equals(Files.readAllBytes(out.resolve("op1/saved-app-before.sab")), deviceApp) && Arrays.equals(Files.readAllBytes(out.resolve("op1/kits-before.scode")), deviceCode), "What the device had is kept in the journal folder");
      String journal = Files.readString(out.resolve("op1/journal.log"));
      check(journal.contains("ATTEMPT STAGE app.sab") && journal.contains("DONE RESTART") && journal.contains("DONE VERIFY"), "Every step is journalled: " + journal);
      // Restore: the backup goes back the same way.
      Project restored = session.deploy(Files.readAllBytes(out.resolve("op1/saved-app-before.sab")), null, out.resolve("op2"), access.password().clone());
      check(named(restored, "sum2") == null && named(restored, "src1").values.get("out").equals("14.5"), "The backup restores the previous application");
      // Refusals that protect access to the device.
      Project noSox = restored.copy();
      for (Project.Node n : noSox.nodes.values()) if (n.type.equals("sox::SoxService")) n.values.put("port", "1999");
      try { AppFiles.forDevice(noSox, restored, session.fetch("app.sab")); throw new AssertionError("SOX port change accepted"); }
      catch (IllegalArgumentException expected) { check(expected.getMessage().contains("sox"), "A changed service is refused and named"); }
      Project noUser = restored.copy();
      noUser.nodes.values().removeIf(n -> n.type.equals("sys::User"));
      try { AppFiles.forDevice(noUser, restored, session.fetch("app.sab")); throw new AssertionError("User removal accepted"); }
      catch (IllegalArgumentException expected) { passed++; }
      Project newUser = restored.copy();
      Project.Node users = null;
      for (Project.Node n : newUser.nodes.values()) if (n.type.equals("sys::UserService")) users = n;
      Project.Node extra = new Project.Node();
      extra.id = "draft-user"; extra.name = "guest"; extra.type = "sys::User"; extra.parent = users.id;
      extra.kitPart = users.kitPart;
      newUser.nodes.put(extra.id, extra);
      try { AppFiles.forDevice(newUser, restored, session.fetch("app.sab")); throw new AssertionError("User without credentials accepted"); }
      catch (IllegalArgumentException expected) { check(expected.getMessage().contains("guest.cred"), "A new component with secrets is refused and named"); }
      // New kit: the code is uploaded together with the app.
      String logic = sdk.kitPart("logic");
      Project wider = restored.copy();
      wider.parts.add(logic);
      Project.Node gate = type(wider.parts, "logic::And2");
      gate.id = "draft-gate"; gate.name = "gate1"; gate.x = 120; gate.y = 480;
      wider.nodes.put(gate.id, gate);
      byte[] widerApp = AppFiles.forDevice(wider, restored, session.fetch("app.sab"));
      byte[] widerCode = Files.readAllBytes(toolchain.buildCode(wider.parts, out.resolve("code")));
      Project widened = session.deploy(widerApp, widerCode, out.resolve("op3"), access.password().clone());
      check(named(widened, "gate1") != null && widened.parts.contains(logic), "A new kit arrives with its code: the device schema grew");
      session.close();
    }
    System.out.println("PASS deploy " + passed + " checks: upload with the device's own credentials, staged files verified by readback, restart and re-login, running values, journal and backups, restore, refusals for services/users/secrets, new kit with code");
  }
}

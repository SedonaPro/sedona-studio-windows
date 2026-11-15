import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.protocol.SoxDeviceSession;
import ru.bccontrol.sedonastudio.storage.AppFiles;
import ru.bccontrol.sedonastudio.toolchain.Sdk;
import ru.bccontrol.sedonastudio.toolchain.Toolchain;

/** External SDK: find it, build an image, run the VM on loopback 13987 and read the app back over SOX. */
public final class ToolchainTests {
  static int passed;
  static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); passed++; }
  static String shape(Project p) {
    Map<String, String> path = new HashMap<>();
    for (Project.Node n : p.nodes.values()) {
      String s = n.name; Project.Node at = n;
      while (!at.id.equals("0")) { at = p.nodes.get(at.parent); s = at.name + "/" + s; }
      path.put(n.id, s);
    }
    List<String> lines = new ArrayList<>();
    for (Project.Node n : p.nodes.values()) lines.add(path.get(n.id) + " : " + n.type + " : " + n.kitPart);
    for (Project.Wire w : p.wires) lines.add(path.get(w.source) + "." + w.sourcePort + " -> " + path.get(w.target) + "." + w.targetPort);
    Collections.sort(lines);
    return String.join("\n", lines);
  }

  public static void main(String[] args) throws Exception {
    // SOX client threads are not daemons: always end the process, whatever happened.
    try { run(args); } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
    System.exit(0);
  }

  static void run(String[] args) throws Exception {
    Path out = Paths.get(args[0]), fixtureSax = Paths.get(args[1]), sdkRoot = Paths.get(args[2]);
    check(Sdk.at(out.resolve("no-such-folder")).isEmpty() && Sdk.at(out.getRoot()).isEmpty(), "A folder that is not an SDK is not taken for one");
    Sdk sdk = Sdk.at(sdkRoot).orElseThrow(() -> new AssertionError("SDK not recognised: " + sdkRoot));
    check(sdk.vm != null && Files.isRegularFile(sdk.home.resolve("lib/sedonac.jar")), "SDK with compiler and VM");
    Toolchain toolchain = new Toolchain(sdk);
    Project project = AppFiles.read(fixtureSax);
    // Exact kit builds or nothing.
    try { toolchain.buildCode(List.of("sys-00000001"), out.resolve("refused")); throw new AssertionError("Unknown kit build accepted"); }
    catch (java.io.IOException expected) { check(expected.getMessage().contains("sys-00000001"), "Missing kit build is named"); }
    check(!Files.exists(out.resolve("refused/kits.scode")), "Nothing built after a refusal");
    // Image from the project alone.
    Path image = out.resolve("image");
    List<String> defaulted = toolchain.buildImage(project, image);
    check(Files.size(image.resolve("kits.scode")) > 1000 && Files.size(image.resolve("app.sab")) > 50, "Image built: kits.scode and app.sab");
    check(defaulted.stream().anyMatch(s -> s.endsWith(".cred")), "Secrets are not put into the image");
    check(shape(AppFiles.read(image.resolve("app.sab"))).equals(shape(project)), "The image holds the project");
    // Run the fixture app (it has a test user) on the code built here and read it back over SOX.
    Path run = out.resolve("run");
    toolchain.buildCode(project.parts, run);
    Files.copy(fixtureSax, run.resolve("app.sax"), StandardCopyOption.REPLACE_EXISTING);
    toolchain.compile(run.resolve("app.sax"), run.resolve("app.log"));
    try { toolchain.start(out.resolve("refused")); throw new AssertionError("Started without an image"); }
    catch (java.io.IOException expected) { passed++; }
    Project device = null;
    try (Toolchain.Vm vm = toolchain.start(run)) {
      SoxDeviceSession session = new SoxDeviceSession();
      Exception last = null;
      for (int attempt = 0; attempt < 40 && device == null; attempt++) {
        if (!vm.alive()) throw new AssertionError("VM exited:\n" + vm.output());
        try { device = session.connect("127.0.0.1", 13987, "admin", "localJarTest".toCharArray()); }
        catch (Exception notYet) { last = notYet; Thread.sleep(250); }
      }
      if (device == null) throw new AssertionError("No SOX connection to the started VM: " + last + "\n" + vm.output());
      check(shape(device).equals(shape(project)), "The running VM has the same components, exact kits and links");
      session.close();
      check(vm.alive(), "VM keeps running after the client leaves");
      vm.close();
      check(!vm.alive(), "VM stopped");
    }
    // Simulator image: a bare diagram (two blocks, no root, no services) becomes a runnable app.
    Project bare = new Project();
    Project.Node add = null, source = null;
    sedona.KitPart[] kitParts = new sedona.KitPart[project.parts.size()];
    for (int i = 0; i < kitParts.length; i++) kitParts[i] = sedona.KitPart.parse(project.parts.get(i));
    for (Project.Node n : ru.bccontrol.sedonastudio.storage.SchemaCatalog.types(sedona.Schema.load(kitParts))) {
      if (n.type.equals("math::Add2")) add = n;
      if (n.type.equals("types::ConstFloat")) source = n;
    }
    source.id = "draft-a"; source.name = "src1"; source.x = 120; source.y = 120; source.values.put("out", "14.5");
    add.id = "draft-b"; add.name = "sum1"; add.x = 480; add.y = 120;
    bare.nodes.put(source.id, source); bare.nodes.put(add.id, add);
    for (Project.Node n : bare.nodes.values()) if (!bare.parts.contains(n.kitPart)) bare.parts.add(n.kitPart);
    bare.connect("draft-a", "out", "draft-b", "in1");
    String before = ru.bccontrol.sedonastudio.storage.ProjectStore.encode(bare);
    Path sim = out.resolve("sim");
    ru.bccontrol.sedonastudio.toolchain.SimImage.Access access = ru.bccontrol.sedonastudio.toolchain.SimImage.build(toolchain, bare, sim, 13987);
    check(ru.bccontrol.sedonastudio.storage.ProjectStore.encode(bare).equals(before), "The project itself is not changed by the simulator");
    check(access.parts().get(0).startsWith("sys-") && access.parts().stream().anyMatch(x -> x.startsWith("sox-")), "Kits the simulator needs are added from the SDK");
    check(access.password().length >= 20 && !new String(Files.readAllBytes(sim.resolve("app.sab")), java.nio.charset.StandardCharsets.ISO_8859_1).contains(new String(access.password())), "One-run password, not stored in clear");
    Project running = null;
    try (Toolchain.Vm vm = toolchain.start(sim)) {
      SoxDeviceSession session = new SoxDeviceSession();
      Exception last = null;
      for (int attempt = 0; attempt < 40 && running == null; attempt++) {
        if (!vm.alive()) throw new AssertionError("Simulator exited:\n" + vm.output());
        try { running = session.connect("127.0.0.1", access.port(), access.user(), access.password().clone()); }
        catch (Exception notYet) { last = notYet; Thread.sleep(250); }
      }
      if (running == null) throw new AssertionError("No SOX connection to the simulator: " + last + "\n" + vm.output());
      Project.Node src = null, sum = null;
      for (Project.Node n : running.nodes.values()) { if (n.name.equals("src1")) src = n; if (n.name.equals("sum1")) sum = n; }
      { StringBuilder names = new StringBuilder(); for (Project.Node n : running.nodes.values()) names.append(n.id).append(":").append(n.name).append(" "); check(src != null && sum != null && src.values.get("out").equals("14.5") && src.x == 120 && sum.x == 480, "The diagram runs in the simulator with its values and positions: " + names); }
      final String a = src.id, b = sum.id;
      check(running.wires.stream().anyMatch(w -> w.source.equals(a) && w.target.equals(b) && w.targetPort.equals("in1")), "Its link is there");
      // The simulator must not be reachable from the network: its port is free on other addresses.
      java.net.InetAddress outside = null;
      for (java.net.NetworkInterface nic : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()))
        for (java.net.InetAddress address : java.util.Collections.list(nic.getInetAddresses()))
          if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress() && outside == null) outside = address;
      if (outside != null)
        try (java.net.DatagramSocket probe = new java.net.DatagramSocket(new java.net.InetSocketAddress(outside, access.port()))) { passed++; }
        catch (java.net.BindException taken) { throw new AssertionError("The simulator listens on " + outside + " too, not only on loopback"); }
      Thread.sleep(600);
      check("14.5".equals(session.sample(b).get("out")) || session.sample(b).get("out").startsWith("14.5"), "The app is executing: sum1.out follows src1.out");
      session.close();
    }
    try { ru.bccontrol.sedonastudio.toolchain.SimImage.build(toolchain, AlienKit.project(bare), out.resolve("refused-sim"), 13987); throw new AssertionError("Foreign kit build accepted"); }
    catch (java.io.IOException expected) { check(expected.getMessage().contains("math-00000001"), "A kit build the SDK lacks is named"); }
    // The SDK's own sample app, when it has one: many kits, some compiled against kits the app does not name.
    Path sample = sdkRoot.resolve("build/linux-amd64-full-sim/app.sax");
    if (Files.isRegularFile(sample)) {
      Project big = AppFiles.read(sample, sdk.kitSource());
      ru.bccontrol.sedonastudio.toolchain.SimImage.Access bigAccess = ru.bccontrol.sedonastudio.toolchain.SimImage.build(toolchain, big, out.resolve("sample"), 13987);
      check(bigAccess.parts().size() > big.parts.size(), "Kits that the app's kits depend on are added to the code");
      try (Toolchain.Vm vm = toolchain.start(out.resolve("sample"))) {
        SoxDeviceSession session = new SoxDeviceSession();
        Project read = null; Exception last = null;
        for (int attempt = 0; attempt < 60 && read == null; attempt++) {
          if (!vm.alive()) throw new AssertionError("Sample app exited:\n" + vm.output());
          try { read = session.connect("127.0.0.1", bigAccess.port(), bigAccess.user(), bigAccess.password().clone()); }
          catch (Exception notYet) { last = notYet; Thread.sleep(250); }
        }
        if (read == null) throw new AssertionError("No SOX connection to the sample app: " + last + "\n" + vm.output());
        check(read.nodes.size() == big.nodes.size() + 1, "The SDK sample app runs in the simulator with one extra user: " + read.nodes.size() + " vs " + big.nodes.size());
        session.close();
      }
    }
    System.out.println("PASS toolchain " + passed + " checks: SDK detection, exact-kit refusal, image from a project, VM start on loopback, SOX readback equals the source, VM stop; simulator image from a bare diagram with a one-run user, running values");
  }
  static final class AlienKit {
    static Project project(Project from) {
      Project p = from.copy();
      for (Project.Node n : p.nodes.values()) if (n.kitPart.startsWith("math-")) n.kitPart = "math-00000001";
      p.parts.replaceAll(x -> x.startsWith("math-") ? "math-00000001" : x);
      return p;
    }
  }
}

import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.SecretFields;
import ru.bccontrol.sedonastudio.storage.AppFiles;

/** app.sax / app.sab without a device. Runs with -Dsedona.home pointing at an SDK that has manifests. */
public final class AppFileTests {
  static int passed;
  static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); passed++; }
  static Project.Node named(Project p, String name) {
    for (Project.Node n : p.nodes.values()) if (n.name.equals(name)) return n;
    throw new AssertionError("No block " + name);
  }
  static String shape(Project p) {
    // Structure and configuration by path, independent of numeric ids.
    Map<String, String> path = new HashMap<>();
    for (Project.Node n : p.nodes.values()) {
      String s = n.name; Project.Node at = n;
      while (!at.id.equals("0")) { at = p.nodes.get(at.parent); s = at.name + "/" + s; }
      path.put(n.id, s);
    }
    List<String> lines = new ArrayList<>();
    for (Project.Node n : p.nodes.values()) {
      StringBuilder b = new StringBuilder(path.get(n.id) + " : " + n.type + " @" + Math.round(n.x / 12.0) + "," + Math.round(n.y / 12.0));
      for (Project.Port port : n.ports) if (port.config) b.append(' ').append(port.name).append('=').append(n.values.get(port.name));
      lines.add(b.toString());
    }
    for (Project.Wire w : p.wires) lines.add(path.get(w.source) + "." + w.sourcePort + " -> " + path.get(w.target) + "." + w.targetPort);
    Collections.sort(lines);
    return String.join("\n", lines);
  }

  public static void main(String[] args) throws Exception {
    Path out = Paths.get(args[0]), sample = Paths.get(args[1]);
    Files.createDirectories(out);
    Project p = AppFiles.read(sample);
    check(p.nodes.size() > 3 && p.nodes.containsKey("0") && !p.parts.isEmpty(), "Sample app read");
    for (Project.Node n : p.nodes.values())
      for (String field : n.values.keySet()) check(!SecretFields.matches(field), "No secret values in the project");
    check(p.validate().isEmpty(), "Imported project is valid: " + p.validate());
    // Out and back, through both formats.
    List<String> defaulted = AppFiles.write(p, out.resolve("copy.sax"));
    check(defaulted.stream().anyMatch(s -> s.endsWith(".cred")), "Secret fields are reported, not exported");
    AppFiles.write(p, out.resolve("copy.sab"));
    Project fromXml = AppFiles.read(out.resolve("copy.sax")), fromBinary = AppFiles.read(out.resolve("copy.sab"));
    check(shape(p).equals(shape(fromXml)), "SAX round trip");
    check(shape(p).equals(shape(fromBinary)), "SAB round trip");
    check(fromBinary.parts.equals(p.parts), "Exact kits kept");
    for (String id : p.nodes.keySet()) check(fromBinary.nodes.containsKey(id), "Component ids kept");
    // Blocks added in the editor: draft ids, a value, a position, a link.
    Project draft = p.copy();
    Project.Node add = null, source = null;
    for (Project.Node n : ru.bccontrol.sedonastudio.storage.SchemaCatalog.types(sedona.Schema.load(parts(p)))) {
      if (n.type.equals("math::Add2")) add = n;
      if (n.type.equals("types::ConstFloat")) source = n;
    }
    check(add != null && source != null, "Palette types from the app's kits");
    source.id = "draft-a"; source.name = "src1"; source.x = 240; source.y = 360; source.values.put("out", "14.5");
    add.id = "draft-b"; add.name = "sum1"; add.x = 600; add.y = 360;
    draft.nodes.put(source.id, source); draft.nodes.put(add.id, add);
    draft.connect("draft-a", "out", "draft-b", "in1");
    AppFiles.write(draft, out.resolve("draft.sab"));
    Project back = AppFiles.read(out.resolve("draft.sab"));
    Project.Node src = named(back, "src1"), sum = named(back, "sum1");
    check(src.id.matches("[0-9]+") && !p.nodes.containsKey(src.id), "New block got a free numeric id");
    check(src.values.get("out").equals("14.5") && src.x == 240 && src.y == 360, "Value and position survive");
    check((Integer.parseInt(src.values.get("meta")) & 15) == 1, "A new block is put into security group 1, not into none");
    check(back.wires.stream().anyMatch(w -> w.source.equals(src.id) && w.sourcePort.equals("out") && w.target.equals(sum.id) && w.targetPort.equals("in1")), "Link survives");
    check(back.nodes.size() == p.nodes.size() + 2, "Nothing else changed");
    // Unknown kit: a clear refusal, nothing guessed.
    Files.writeString(out.resolve("alien.sax"), "<sedonaApp><schema><kit name=\"sys\"/><kit name=\"nokit\" checksum=\"1234abcd\"/></schema><app/><links/></sedonaApp>");
    try { AppFiles.read(out.resolve("alien.sax")); throw new AssertionError("Unknown kit accepted"); }
    catch (java.io.IOException expected) { check(expected.getMessage().contains("nokit-1234abcd"), "Missing kit is named"); }
    try { AppFiles.read(out.resolve("project.json")); throw new AssertionError("Wrong extension accepted"); }
    catch (IllegalArgumentException expected) { passed++; }
    System.out.println("PASS app files " + passed + " checks: SAX/SAB read, secrets dropped and reported, round trip through both formats, ids kept, editor blocks exported, unknown kit refused");
  }
  static sedona.KitPart[] parts(Project p) {
    sedona.KitPart[] parts = new sedona.KitPart[p.parts.size()];
    for (int i = 0; i < parts.length; i++) parts[i] = sedona.KitPart.parse(p.parts.get(i));
    return parts;
  }
}

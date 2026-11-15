import java.nio.file.*;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.storage.AppFiles;
import ru.bccontrol.sedonastudio.storage.ProjectStore;
import ru.bccontrol.sedonastudio.storage.SchemaCatalog;

/** Writes a small project (constant -> adder, with a note and a wire bend) on the SDK's exact kits. */
public final class PrepareSimProject {
  public static void main(String[] args) throws Exception {
    Project kits = AppFiles.read(Paths.get(args[0]));
    sedona.KitPart[] parts = new sedona.KitPart[kits.parts.size()];
    for (int i = 0; i < parts.length; i++) parts[i] = sedona.KitPart.parse(kits.parts.get(i));
    Project p = new Project();
    p.name = "SimTest";
    Project.Node add = null, source = null;
    for (Project.Node n : SchemaCatalog.types(sedona.Schema.load(parts))) {
      if (n.type.equals("math::Add2")) add = n;
      if (n.type.equals("types::ConstFloat")) source = n;
    }
    source.id = "draft-a"; source.name = "src1"; source.x = 120; source.y = 120; source.values.put("out", "14.5"); source.note = "keep me"; source.width = 300; source.icon = "★";
    add.info = true; add.width = 264;
    add.id = "draft-b"; add.name = "sum1"; add.x = 480; add.y = 120;
    p.nodes.put(source.id, source); p.nodes.put(add.id, add);
    for (Project.Node n : p.nodes.values()) if (!p.parts.contains(n.kitPart)) p.parts.add(n.kitPart);
    p.connect("draft-a", "out", "draft-b", "in1");
    p.wires.get(0).bends.add(new Project.Point(360, 240));
    ProjectStore.save(p, Paths.get(args[1]));
    System.out.println("PASS simulator project prepared on the SDK kits");
  }
}

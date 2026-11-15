import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.*;
import ru.bccontrol.sedonastudio.storage.*;
import sedona.*;
import sedona.manifest.*;
public class TemplatePortabilityTests {
 static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
 public static void main(String[] args)throws Exception {
  Path home=Path.of(args[0]);Files.createDirectories(home.resolve("lib"));Files.writeString(home.resolve("lib/sedona.properties"),"");System.setProperty("sedona.home",home.toString());
  LocalCatalog.Result catalog=LocalCatalog.load(List.of());
  Project p=new Project();p.parts.addAll(List.of("sys-d3984c51","types-10936551","math-c22b255c","logic-9fe95ce1"));
  Project.Node a=catalog.types().stream().filter(n->n.type.equals("types::ConstFloat")).findFirst().orElseThrow().copy(),b=catalog.types().stream().filter(n->n.type.equals("math::Add2")).findFirst().orElseThrow().copy();
  a.id="a";a.name="src";a.x=24;a.y=24;b.id="b";b.name="sum";b.x=400;b.y=24;p.nodes.put(a.id,a);p.nodes.put(b.id,b);p.connect("a","out","b","in1");p.wires.get(0).bends.add(new Project.Point(350,100));
  String original=ProjectStore.encode(p);var reduced=TemplateRequirements.resolve(p);
  check(reduced.verified()&&reduced.project().parts.equals(List.of("sys-d3984c51","types-10936551","math-c22b255c")),"Unrelated kit removed; exact ordered closure");
  check(original.equals(ProjectStore.encode(p)),"Original parts unchanged");
  Project hvac=new Project();hvac.parts.addAll(catalog.dependencies().keySet());
  Project.Node block=catalog.types().stream().filter(n->n.kitPart.equals("hvac-7264c67c")).findFirst().orElseThrow().copy();block.id="h";block.name="hvac";hvac.nodes.put(block.id,block);
  var transitive=TemplateRequirements.resolve(hvac);check(transitive.verified()&&new HashSet<>(transitive.project().parts).equals(new HashSet<>(catalog.dependencies().get(block.kitPart))),"Transitive closure");
  Path manifest=ManifestDb.toFile(KitPart.parse("math-c22b255c")).toPath();byte[] bytes=Files.readAllBytes(manifest);Files.delete(manifest);
  var missing=TemplateRequirements.resolve(p);check(!missing.verified()&&missing.project().parts.equals(p.parts),"Missing manifest preserves full set");Files.write(manifest,bytes);
  Project wrong=p.copy();wrong.parts.set(2,"math-00000001");wrong.nodes.get("b").kitPart="math-00000001";
  var absent=TemplateRequirements.resolve(wrong);check(!absent.verified()&&absent.project().parts.contains("math-00000001")&&!absent.project().parts.contains("math-c22b255c"),"No checksum substitution");
  Project conflicting=p.copy();conflicting.parts.add("math-00000001");check(!TemplateRequirements.resolve(conflicting).verified(),"Duplicate versions rejected");
  Project fragment=TemplateStore.prepare(p,Set.of("a","b"),"Pair");Project target=p.copy();String targetBefore=ProjectStore.encode(target),fragmentBefore=ProjectStore.encode(fragment);
  Project placed=TemplatePlacement.place(target,fragment,"0");check(placed.nodes.get("b").x-placed.nodes.get("a").x==376,"Group geometry retained");
  for(Project.Node n:placed.nodes.values())for(Project.Node old:target.nodes.values()) {
   double nh=61+24*n.ports.size(),oh=61+24*old.ports.size();
   check(n.x>=old.x+220+12||n.x+220+12<=old.x||n.y>=old.y+oh+12||n.y+nh+12<=old.y,"Inserted card overlap");
  }
  double dx=placed.nodes.get("a").x-fragment.nodes.get("a").x,dy=placed.nodes.get("a").y-fragment.nodes.get("a").y;
  check(placed.wires.get(0).bends.get(0).x()==fragment.wires.get(0).bends.get(0).x()+dx&&placed.wires.get(0).bends.get(0).y()==fragment.wires.get(0).bends.get(0).y()+dy,"Manual route translated");
  check(targetBefore.equals(ProjectStore.encode(target))&&fragmentBefore.equals(ProjectStore.encode(fragment)),"Placement detached");
  Project elsewhere=TemplatePlacement.place(target,fragment,"other");check(elsewhere.nodes.get("a").x==24&&elsewhere.nodes.get("a").y==24,"Other-folder cards ignored");
  Project huge=fragment.copy();huge.nodes.get("b").x=5000;boolean rejected=false;try{TemplatePlacement.place(target,huge,"0");}catch(IllegalArgumentException ex){rejected=true;}check(rejected,"No room explicit failure");
  System.out.println("PASS template portability: exact direct/transitive closure, unrelated kit removal, missing/conflicting/checksum fallback without substitutions");
  System.out.println("PASS template placement: free area, folder isolation, relative geometry, manual bends, detached inputs and no-room rejection");
 }
}

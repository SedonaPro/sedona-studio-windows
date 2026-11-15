import ru.bccontrol.sedonastudio.model.*;
public class ProjectDiffTests {
 public static void main(String[] args) {
  Project a=new Project(); Project.Node n=new Project.Node(); n.id="1";n.name="ramp";n.type="func::Ramp";n.kitPart="func-12345678";
  for(String name:new String[]{"min","out","password"}) {Project.Port p=new Project.Port();p.name=name;p.readonly=name.equals("out");n.ports.add(p);n.values.put(name,"1");}
  a.nodes.put(n.id,n);Project b=a.copy();b.nodes.get("1").values.put("out","2");b.nodes.get("1").values.put("password","secret");b.nodes.get("1").note="local";
  if(!ProjectDiff.compare(a,b).isEmpty())throw new AssertionError("Runtime, secret or note in review");
  b.nodes.get("1").values.put("min","2");var rows=ProjectDiff.compare(a,b);
  if(rows.size()!=1||!rows.get(0).before().equals("1")||!rows.get(0).after().equals("2"))throw new AssertionError("Old/new values");
  b.nodes.get("1").name="renamed"; b.wires.add(new Project.Wire("1","out","1","min"));
  if(ProjectDiff.compare(a,b).size()!=3)throw new AssertionError("Rename/link");
  if(!ProjectDiff.compare(b,a).stream().anyMatch(c->c.kind().equals(ru.bccontrol.sedonastudio.model.Tr.of("diff.removal"))))throw new AssertionError("Removed link");
  if(!a.nodes.get("1").name.equals("ramp"))throw new AssertionError("Mutated baseline");
  System.out.println("PASS project comparison: old/new, runtime/secret/local exclusion, rename/link and immutable baseline");
 }
}

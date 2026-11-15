import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.storage.*;
import ru.bccontrol.sedonastudio.model.Project;
public class LocalCatalogTests {
 public static void main(String[] args)throws Exception{
  Path home=Paths.get(args[0]);Files.createDirectories(home.resolve("lib"));Files.writeString(home.resolve("lib/sedona.properties"),"# isolated catalog test\n");System.setProperty("sedona.home",home.toString());
  LocalCatalog.Result result=LocalCatalog.load(List.of());
  if(result.types().size()<50)throw new AssertionError("Full manifest catalog missing: "+result.types().size()+" "+result.warnings());
  if(result.types().stream().noneMatch(n->n.type.equals("func::Ramp")))throw new AssertionError("Ramp missing");
  if(result.types().stream().filter(n->n.type.equals("func::Ramp")).noneMatch(n->n.typeIcon.equals("module://icons/x16/control/util/sine.png")))throw new AssertionError("Manifest icon facet lost");
  if(result.types().stream().anyMatch(n->n.type.equals("sys::Component")||n.type.equals("sys::App")||n.type.equals("sys::User")))throw new AssertionError("Hidden types exposed");
  for(Project.Node n:result.types()){
   if(!result.dependencies().containsKey(n.kitPart))throw new AssertionError("Missing dependency closure");
   if(n.ports.stream().anyMatch(p->p.name.equals("meta")||p.name.toLowerCase().contains("password")))throw new AssertionError("Private ports exposed");
   if(result.dependencies().get(n.kitPart).stream().noneMatch(p->p.startsWith("sys-")))throw new AssertionError("Missing sys closure");
  }
  System.out.println("PASS manifest palette: "+result.types().size()+" types, "+result.dependencies().size()+" exact kit versions, inherited slots, dependencies, hidden abstract/service/private types; warnings="+result.warnings().size());
 }
}

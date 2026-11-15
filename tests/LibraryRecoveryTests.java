import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.*;
import ru.bccontrol.sedonastudio.storage.*;
public class LibraryRecoveryTests {
 static void check(boolean condition,String text){if(!condition)throw new AssertionError(text);}
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]);Files.createDirectories(root);
  Project source=new Project();var bundled=Catalog.bundled();
  Project.Node a=bundled.stream().filter(n->n.type.equals("types::ConstFloat")).findFirst().orElseThrow().copy();
  Project.Node b=bundled.stream().filter(n->n.type.equals("math::Add2")).findFirst().orElseThrow().copy();
  a.id="a";a.name="src";a.x=200;a.y=100;a.values.put("password","not-for-storage");
  b.id="b";b.name="sum";b.x=500;b.y=100;
  Project.Node c=b.copy();c.id="c";c.name="sink";c.x=900;
  for(Project.Node n:List.of(a,b,c)){source.nodes.put(n.id,n);if(!source.parts.contains(n.kitPart))source.parts.add(n.kitPart);}
  source.connect("a","out","b","in1");source.connect("b","out","c","in1");
  String original=ProjectStore.encode(source);
  Project fragment=TemplateStore.prepare(source,Set.of("a","b"),"Sample");
  check(fragment.nodes.size()==2&&fragment.wires.size()==1,"Internal links only");
  check(fragment.nodes.get("a").x==24&&fragment.nodes.get("a").y==24,"Normalized positions");
  check(!fragment.nodes.get("a").values.containsKey("password"),"Secret stripped");
  check(original.equals(ProjectStore.encode(source)),"Source unchanged");
  TemplateStore library=new TemplateStore(root.resolve("templates"));library.add(fragment);
  TemplateStore second=new TemplateStore(library.root());check(second.list().size()==1,"Persistent across store instances");
  var entry=second.list().get(0);String beforeRename=Files.readString(entry.path());second.rename(entry,"Renamed");check(second.list().get(0).name().equals("Renamed"),"Persistent rename");check(Files.readString(entry.path().resolveSibling(entry.path().getFileName()+".bak")).equals(beforeRename),"Rename backup");Project renamed=second.load(entry);renamed.name="Sample";check(ProjectStore.encode(renamed).equals(beforeRename),"Rename retains graph/parts");String afterRename=Files.readString(entry.path());boolean invalidName=false;try{second.rename(entry," ");}catch(IllegalArgumentException ex){invalidName=true;}check(invalidName&&Files.readString(entry.path()).equals(afterRename),"Invalid rename unchanged");Project loaded=second.load(entry);History h=new History();h.reset(new Project());
  h.edit(p->EditorCommands.paste(p,loaded,"0",36));check(h.get().nodes.size()==2&&h.get().wires.size()==1,"Template paste");
  h.edit(p->EditorCommands.paste(p,loaded,"0",36));check(h.get().nodes.size()==4&&h.get().validate().isEmpty(),"Unique names and IDs");
  check(h.undo()&&h.get().nodes.size()==2,"Atomic undo");
  Files.writeString(library.root().resolve("broken.sedona.json"),"{bad");check(library.list().size()==2&&library.list().stream().anyMatch(e->!e.error().isEmpty()),"Corrupt isolated");
  Path archived=library.remove(entry);check(Files.exists(archived)&&library.list().size()==1,"Recoverable deletion");
  Path outside=root.resolve("outside.sedona.json");ProjectStore.save(fragment,outside);boolean rejected=false;
  try{library.remove(new TemplateStore.Entry(outside,"outside",0,0,""));}catch(Exception ex){rejected=true;}check(rejected&&Files.exists(outside),"Path guard");
  Project conflict=new Project();conflict.parts.add(a.kitPart.substring(0,a.kitPart.lastIndexOf('-'))+"-00000000");h.reset(conflict);String unchanged=ProjectStore.encode(h.get());rejected=false;
  try{h.edit(p->EditorCommands.paste(p,fragment,"0",36));}catch(IllegalArgumentException ex){rejected=true;}check(rejected&&unchanged.equals(ProjectStore.encode(h.get())),"Exact kit conflict atomic");
  Path op=root.resolve("operations/01");Files.createDirectories(op);OperationJournal journal=new OperationJournal(op);journal.attempt("ADD src");
  var pending=RecoveryStore.inspect(op);check(pending.pending().equals("ADD src")&&pending.attempted()==1&&pending.completed()==0&&pending.state().contains(ru.bccontrol.sedonastudio.model.Tr.of("recovery.required")),"Unknown outcome");
  journal.complete("ADD src");Files.writeString(op.resolve("completed.log"),"ADD src");
  check(RecoveryStore.inspect(op).state().contains(ru.bccontrol.sedonastudio.model.Tr.of("recovery.required")),"No success without readback");
  ProjectStore.save(fragment,op.resolve("readback.sedona.json"));check(RecoveryStore.inspect(op).state().equals(ru.bccontrol.sedonastudio.model.Tr.of("recovery.verified")),"Verified complete");
  Files.writeString(op.resolve("partial.log"),"");check(RecoveryStore.inspect(op).state().contains(ru.bccontrol.sedonastudio.model.Tr.of("recovery.required")),"Partial overrides completed");
  Files.delete(op.resolve("partial.log"));Files.writeString(op.resolve("readback.sedona.json"),"broken");check(RecoveryStore.inspect(op).state().contains(ru.bccontrol.sedonastudio.model.Tr.of("recovery.required")),"Corrupt readback rejected");
  Path prepared=root.resolve("operations/02");Files.createDirectories(prepared);new OperationJournal(prepared);check(RecoveryStore.inspect(prepared).state().contains(ru.bccontrol.sedonastudio.model.Tr.of("recovery.notStarted")),"Prepared-only state");
  check(new RecoveryStore(root.resolve("operations")).list().size()==2,"Operation listing");
  System.out.println("PASS template rename: persistent name, graph/kit preservation, prior-file backup and invalid-name atomicity");
  System.out.println("PASS template library: persistence, internal wires, placement, secrets, unique paste/undo, exact kit conflicts, corruption isolation and recoverable deletion");
  System.out.println("PASS recovery journal: pending intent, confirmed steps, mandatory valid readback, partial precedence, prepared-only state and operation listing");
 }
}

import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import ru.bccontrol.sedonastudio.model.*;
import ru.bccontrol.sedonastudio.storage.*;

public final class AnnotationTests {
 static int count;
 static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);count++;}
 public static void main(String[] args)throws Exception{
  Project p=new Project();Project.Node n=new Project.Node();n.id="a";n.name="block";n.type="math::Add2";n.kitPart="math-12345678";n.x=100;n.y=100;
  n.typeIcon="module://icons/x16/control/math/add.png";n.icon="!";n.info=true;n.infoSource="type";n.note="instance comment";p.nodes.put(n.id,n);p.parts.add(n.kitPart);
  Project.Caption c=new Project.Caption();c.id="note-a";c.anchor="a";c.text="standalone note";c.color="blue";c.x=400;c.y=100;p.captions.add(c);
  Project round=ProjectStore.decode(ProjectStore.encode(p));
  check(round.nodes.get("a").typeIcon.equals(n.typeIcon) && round.nodes.get("a").infoSource.equals("type"),"type metadata and info source persist independently of marker");
  check(round.captions.get(0).anchor.equals("a")&&round.captions.get(0).color.equals("blue"),"caption binding/colour persist");
  Map<String,Object> oldData=Json.object(ProjectStore.encode(p));
  @SuppressWarnings("unchecked") Map<String,Object> oldNode=(Map<String,Object>)((List<?>)oldData.get("nodes")).get(0);
  oldNode.remove("infoSource");oldNode.remove("typeIcon");
  @SuppressWarnings("unchecked") Map<String,Object> oldCaption=(Map<String,Object>)((List<?>)oldData.get("captions")).get(0);
  oldCaption.remove("anchor");oldCaption.remove("color");
  Project legacy=ProjectStore.decode(Json.write(oldData));
  check(legacy.nodes.get("a").note.equals(n.note)&&legacy.nodes.get("a").infoSource.equals("auto")&&legacy.captions.get(0).anchor.isEmpty()&&legacy.captions.get(0).color.equals("paper"),"legacy comments and appearance defaults preserved");
  History h=new History();h.reset(round);h.edit(v->EditorCommands.move(v,Map.of("a",new double[]{160,180})));
  check(h.get().captions.get(0).x==460&&h.get().captions.get(0).y==180,"caption follows anchor");
  h.undo();check(h.get().captions.get(0).x==400,"one-step undo includes note");h.redo();
  Project clip=EditorCommands.fragment(h.get(),Set.of("a"));
  check(clip.captions.size()==1,"copy includes attached caption");
  var pasted=EditorCommands.pasteMap(h.get(),clip,"0",24);
  var newNote=h.get().captions.get(1);
  check(newNote.anchor.equals(pasted.get("a"))&&!newNote.id.equals(c.id)&&newNote.x==484,"paste remaps caption id/anchor and position");
  h.get().remove("a");
  check(h.get().captions.get(0).anchor.isEmpty()&&h.get().captions.get(0).text.equals(c.text),"deletion detaches note without losing text");
  check(h.get().validate().isEmpty(),"result validates");
  check(IconPack.key(n.typeIcon).equals("icons/x16/control/math/add.png"),"module URI resolved");
  for(String bad:List.of("https://host/icon.png","module://icons/../../secret.png","module://icons/x16/../secret.png","module://icons/x16/a.png?url=x","file:///secret.png","module://icons/x16/a.svg","module://icons/x16/a.png\\evil"))
   check(IconPack.key(bad).isEmpty(),"unsafe URI refused: "+bad);
  Path dir=Path.of(args[0]);Files.createDirectories(dir);Path jar=dir.resolve("icons-ux.jar");
  try(var zip=new ZipOutputStream(Files.newOutputStream(jar))){
   zip.putNextEntry(new ZipEntry("x16/a.png"));zip.write(new byte[]{1,2,3});zip.closeEntry();
   zip.putNextEntry(new ZipEntry("../bad.png"));zip.write(new byte[]{4});zip.closeEntry();
   zip.putNextEntry(new ZipEntry("x16/big.png"));zip.write(new byte[128*1024+1]);zip.closeEntry();
   zip.putNextEntry(new ZipEntry("module.class"));zip.write(new byte[]{1});zip.closeEntry();
  }
  Map<String,byte[]> entries=IconPack.read(jar);
  check(entries.size()==1&&entries.containsKey("icons/x16/a.png"),"archive reads only bounded image resources; no extraction or class loading");
  check(Files.list(dir).count()==1,"archive never extracted");
  System.out.println("PASS annotations "+count+" checks: metadata, legacy notes, move/undo/copy/paste/delete, bounded local image resource parsing");
 }
}

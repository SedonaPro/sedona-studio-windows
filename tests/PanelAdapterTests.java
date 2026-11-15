import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import ru.bccontrol.sedonastudio.model.*;
import ru.bccontrol.sedonastudio.integration.panel.*;
import ru.bccontrol.panel.api.*;
import static ru.bccontrol.panel.api.PanelData.*;
public class PanelAdapterTests {
 static void check(boolean b,String text){if(!b)throw new AssertionError(text);}
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]);PanelDocumentStore a=new PanelDocumentStore(root.resolve("a/main.properties")),b=new PanelDocumentStore(root.resolve("b/main.properties"));
  check(a.load().isEmpty(),"Empty document");a.save("version=1\ncount=0\n");a.save("version=1\ncount=1\n");check(Files.readString(root.resolve("a/main.properties.bak")).contains("count=0"),"Atomic document backup");check(b.load().isEmpty(),"Session isolation");
  boolean rejected=false;try{a.save("x".repeat(262145));}catch(Exception ex){rejected=true;}check(rejected&&a.load().contains("count=1"),"Oversized document unchanged");
  Project p=new Project();Project.Node folder=new Project.Node();folder.id="f";folder.name="plant";folder.parent="0";folder.type="sys::Folder";p.nodes.put("f",folder);
  Project.Node n=new Project.Node();n.id="7";n.name="pump";n.parent="f";n.type="mock::Pump";n.kitPart="mock-00000001";
  for(String name:List.of("value","password","meta")){Project.Port port=new Project.Port();port.name=name;port.type="sys::float";n.ports.add(port);}p.nodes.put(n.id,n);
  PanelSnapshots snapshots=new PanelSnapshots();Ref value=new Ref("/plant/pump","value"),password=new Ref("/plant/pump","password");
  Snapshot dto=snapshots.create(p,"a",false,Set.of(value,password),(node,field)->new Sample("12",Quality.OFFLINE,0,"last value"));
  check(dto.components().size()==1&&dto.components().get(0).fields().size()==1&&!dto.values().containsKey(password)&&dto.values().get(value).value().equals("12"),"Secret/meta exclusion and values");
  check(dto.capabilities().equals(Set.of(Capability.READ))&&!dto.components().get(0).fields().get(0).writable(),"Read-only adapter capabilities");
  check(snapshots.ids(p,Set.of(value)).equals(List.of("7")),"Path resolves to private ID");
  rejected=false;try{dto.values().clear();}catch(UnsupportedOperationException ex){rejected=true;}check(rejected,"Immutable snapshot");
  AtomicInteger updates=new AtomicInteger(),errors=new AtomicInteger();PanelBridge bridge=new PanelBridge(()->dto,a,ex->errors.incrementAndGet());PanelHost.Subscription subscription=bridge.subscribe(s->updates.incrementAndGet());
  bridge.watch(Set.of(value));check(bridge.watched().equals(Set.of(value)),"Watch set");bridge.publish();check(updates.get()==2,"Initial and live delivery");
  check(bridge.write(value,"1").toCompletableFuture().get().outcome()==Outcome.REJECTED&&bridge.invoke(value,"").toCompletableFuture().get().outcome()==Outcome.REJECTED,"Production commands rejected without transport");
  subscription.close();bridge.publish();check(updates.get()==2,"Unsubscribe");
  bridge.subscribe(s->{if(updates.incrementAndGet()>3)throw new IllegalStateException("bad widget");});bridge.publish();bridge.publish();check(errors.get()==1,"Faulty listener isolated and detached");
  bridge.close();check(bridge.watched().isEmpty(),"Close releases watches");
  System.out.println("PASS panel adapter: immutable DTOs, stable paths, secret exclusion, READ-only capabilities, watch/subscription lifecycle, failure isolation, session documents and backups");
 }
}

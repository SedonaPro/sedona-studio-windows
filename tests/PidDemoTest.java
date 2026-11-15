import java.nio.file.*;
import java.net.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.*;
import ru.bccontrol.sedonastudio.storage.*;
import ru.bccontrol.sedonastudio.toolchain.*;
import ru.bccontrol.sedonastudio.protocol.*;

/** Real-time loopback check of AdvancedPid.execute and ThermalPlant.execute. */
public final class PidDemoTest {
 public static void main(String[] args) throws Exception {
  Sdk sdk=Sdk.at(Path.of(args[0])).orElseThrow();
  Path out=Path.of(args[1]);Files.createDirectories(out);
  var parts=new sedona.KitPart[]{sedona.KitPart.parse(sdk.kitPart("sys")),sedona.KitPart.parse(sdk.kitPart("mcsMath"))};
  Project project=new Project(); project.name="MCS PID thermal loop";
  Project.Node pid=null,plant=null;
  for(var n:SchemaCatalog.types(sedona.Schema.load(parts))){
   if(n.type.equals("mcsMath::AdvancedPid"))pid=n;
   if(n.type.equals("mcsMath::ThermalPlant"))plant=n;
  }
  if(pid==null || plant==null)throw new AssertionError("New components absent from schema");
  pid.id="draft-pid";pid.name="pid";pid.x=120;pid.y=100;pid.width=300;pid.icon="PID";pid.info=true;
  pid.note="SP=40; output 0..100%; Kp=4; Ti=20s";
  pid.values.put("sp","40.0");pid.values.put("kp","4.0");pid.values.put("ti","20.0");
  plant.id="draft-plant";plant.name="plant";plant.x=650;plant.y=100;plant.width=300;plant.info=true;
  plant.note="Virtual thermal process: ambient 20; gain 0.5; tau 20s";
  project.nodes.put(pid.id,pid);project.nodes.put(plant.id,plant);
  project.parts.add(sdk.kitPart("sys"));project.parts.add(sdk.kitPart("mcsMath"));
  project.connect(pid.id,"out",plant.id,"command");project.connect(plant.id,"out",pid.id,"pv");
  ProjectStore.save(project,Path.of(args[2]));
  Toolchain tc=new Toolchain(sdk);int port;
  try(DatagramSocket socket=new DatagramSocket(0,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
  var access=SimImage.build(tc,project,out,port);
  try(var vm=tc.start(out)){
   SoxDeviceSession session=new SoxDeviceSession();
   try{
    Project actual=null; Exception last=null;
    for(int i=0;i<40 && actual==null;i++){
     if(!vm.alive())throw new AssertionError(vm.output());
     try{actual=session.connect("127.0.0.1",port,access.user(),access.password().clone());}
     catch(Exception e){last=e;Thread.sleep(250);}
    }
    if(actual==null)throw new AssertionError(last);
    String pidId=null,plantId=null;
    for(var n:actual.nodes.values()){if(n.name.equals("pid"))pidId=n.id;if(n.name.equals("plant"))plantId=n.id;}
    if(pidId==null || plantId==null)throw new AssertionError("Components absent from running VM");
    List<String> samples=new ArrayList<>();samples.add("elapsed_s,pv,output,pid_ok,plant_ok");
    float first=Float.NaN,lastPv=0;long start=System.nanoTime();
    for(int i=0;i<6;i++){
     Thread.sleep(1000);
     var p=session.sample(pidId);var t=session.sample(plantId);
     float pv=Float.parseFloat(t.get("out")),output=Float.parseFloat(p.get("out"));
     if(!Float.isFinite(pv)||!Float.isFinite(output)||output<0||output>100
         ||!"true".equals(p.get("ok"))||!"true".equals(t.get("ok")))throw new AssertionError(p+" "+t);
     if(i==0)first=pv;lastPv=pv;
     samples.add((System.nanoTime()-start)/1e9+","+pv+","+output+","+p.get("ok")+","+t.get("ok"));
    }
    Files.write(out.resolve("live.csv"),samples);
    if(lastPv<=first+1.0f || lastPv>=45.0f)throw new AssertionError("Thermal response did not progress: "+first+" -> "+lastPv);
    Files.write(out.resolve("live.csv"),samples);
    System.out.println("PASS real-time PID demo: SOX loopback, both execute methods run, PV "+first+" -> "+lastPv+", bounded output, valid states, VM stops on exit");
   }finally{session.close();Arrays.fill(access.password(),'\0');}
  }
 }
}

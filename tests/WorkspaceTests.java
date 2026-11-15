import java.nio.file.*;import java.util.*;import java.io.*;import ru.bccontrol.sedonastudio.storage.*;import ru.bccontrol.sedonastudio.model.*;
public class WorkspaceTests {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static Path file(Path d,String token){return d.resolve("activate-"+token+".json");}
 public static void main(String[] args)throws Exception {
  Path d=Files.createTempDirectory("studio-mailbox-");long pid=ProcessHandle.current().pid();
  String token=WorkspaceRequest.send(d,"activate","",pid);check(WorkspaceRequest.consume(file(d,token),pid).orElseThrow().action().equals("activate"),"Activation roundtrip");check(!Files.exists(file(d,token)),"Consumed once");
  token=WorkspaceRequest.send(d,"close","",pid+1);check(WorkspaceRequest.consume(file(d,token),pid).isEmpty(),"Old process request ignored");
  token=WorkspaceRequest.send(d,"close","",pid);check(WorkspaceRequest.consume(file(d,token),pid).orElseThrow().action().equals("close"),"Explicit close command");
  token=UUID.randomUUID().toString();Files.writeString(file(d,token),Json.write(ProjectStore.map("created",System.currentTimeMillis()-130000,"connect","")));check(WorkspaceRequest.consume(file(d,token),pid).isEmpty(),"Expired request ignored");
  token=UUID.randomUUID().toString();Files.writeString(file(d,token),Json.write(ProjectStore.map("created",System.currentTimeMillis(),"connect","")));check(WorkspaceRequest.consume(file(d,token),pid).isPresent(),"Legacy activation compatible");
  for(String content:List.of("{bad","x".repeat(9000),"{}")){token=UUID.randomUUID().toString();Path invalid=file(d,token);Files.writeString(invalid,content);boolean rejected=false;try{WorkspaceRequest.consume(invalid,pid);}catch(IOException expected){rejected=true;}check(rejected&&!Files.exists(invalid),"Invalid request quarantined");}
  try(var paths=Files.list(d)){check(paths.filter(p->p.getFileName().toString().startsWith("rejected-")).count()==3,"All malformed requests preserved");}
  Path project=d.resolve("draft.json"),backup=d.resolve("draft.json.bak");Project initial=new Project();initial.name="BackupOnly";ProjectStore.save(initial,backup);check(ProjectStore.loadRecovering(project).name.equals("BackupOnly")&&Files.exists(project),"Missing draft restored");
  Files.writeString(project,"{bad main");Files.writeString(backup,"{bad backup");boolean failed=false;try{ProjectStore.loadRecovering(project);}catch(IOException expected){failed=true;}check(failed,"Both invalid rejected");try(var paths=Files.list(d)){check(paths.filter(p->p.getFileName().toString().contains(".damaged-")).count()==2,"Both unreadable originals archived");}
  System.out.println("PASS mailbox: atomic one-use activation, legacy compatibility, PID/expiry guards, explicit close, malformed/oversized quarantine; recovery: missing main and both-corrupt preservation");
 }
}

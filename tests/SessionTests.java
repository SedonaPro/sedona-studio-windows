import java.nio.file.*;import java.util.*;
import ru.bccontrol.sedonastudio.storage.*;
public class SessionTests {
 public static void main(String[] args)throws Exception{
  System.setProperty("studio.root",args[0]);SessionStore.Profile a=SessionStore.create("Device A"),b=SessionStore.create("Device B");
  if(SessionStore.directory(a.id()).equals(SessionStore.directory(b.id())))throw new AssertionError("Mixed session directories");
  char[] secret="test-only-session-password".toCharArray();String encrypted=SessionStore.encrypt(a.id(),secret);
  SessionStore.save(new SessionStore.Profile(a.id(),a.name(),"127.0.0.1",13987,"admin",encrypted));
  if(Files.readString(SessionStore.directory(a.id()).resolve("profile.json")).contains(new String(secret)))throw new AssertionError("Plaintext secret persisted");
  if(!Arrays.equals(secret,SessionStore.decrypt(SessionStore.read(a.id()))))throw new AssertionError("DPAPI roundtrip");
  boolean rejected=false;try{SessionStore.decrypt(new SessionStore.Profile(b.id(),b.name(),"",13987,"admin",encrypted));}catch(Exception e){rejected=true;}
  if(!rejected)throw new AssertionError("Cross-session secret accepted");
  String token=SessionStore.prepareConnection(a.id(),secret);char[] transferred=SessionStore.consumeConnection(a.id(),token);
  if(!Arrays.equals(secret,transferred))throw new AssertionError("Handoff password mismatch");Arrays.fill(transferred,'\0');
  boolean replay=false;try{SessionStore.consumeConnection(a.id(),token);}catch(Exception expected){replay=true;}if(!replay)throw new AssertionError("Handoff replay accepted");
  System.out.println("PASS single-use confirmed connection: encrypted handoff, consumed once, replay rejected");
  ru.bccontrol.sedonastudio.model.Project draft=new ru.bccontrol.sedonastudio.model.Project();draft.name="ResumeTest";ProjectStore.save(draft,SessionStore.directory("restoretest").resolve("draft.sedona.json"));
  if(SessionStore.matching("127.0.0.1",13987,"admin").stream().noneMatch(p->p.id().equals(a.id())))throw new AssertionError("Existing endpoint lookup");
  Files.writeString(SessionStore.directory(b.id()).resolve("marker.txt"),"preserved");Path archived=SessionStore.remove(b.id());
  if(!Files.exists(archived.resolve("marker.txt"))||SessionStore.list().stream().anyMatch(p->p.id().equals(b.id())))throw new AssertionError("Safe session removal");
  try{SessionStore.remove("local");throw new AssertionError("Library removal accepted");}catch(java.io.IOException expected){}
  SessionStore.Profile locked=SessionStore.create("Locked test");try(var channel=java.nio.channels.FileChannel.open(SessionStore.directory(locked.id()).resolve("session.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()){
    try{SessionStore.remove(locked.id());throw new AssertionError("Open session removal accepted");}catch(java.io.IOException expected){}
  }
  System.out.println("PASS session management: endpoint reuse lookup, recoverable removal, current/library/open guards");
  Arrays.fill(secret,'\0');
  try{SessionStore.directory("../escape");throw new AssertionError("Unsafe ID accepted");}catch(IllegalArgumentException expected){}
  System.out.println("PASS sessions: distinct directories, profile roundtrip, DPAPI ciphertext-only storage, per-session entropy, unsafe ID rejected");
 }
}

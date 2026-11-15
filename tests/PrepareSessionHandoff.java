import java.nio.file.*;import java.util.*;import ru.bccontrol.sedonastudio.storage.*;
public class PrepareSessionHandoff {
 public static void main(String[] args)throws Exception{
  System.setProperty("studio.root",args[0]);SessionStore.Profile p=SessionStore.create("Confirmed loopback connection");
  SessionStore.save(new SessionStore.Profile(p.id(),p.name(),"127.0.0.1",13987,"admin",""));
  char[] secret="localJarTest".toCharArray();String token;try{token=SessionStore.prepareConnection(p.id(),secret);}finally{Arrays.fill(secret,'\0');}
  Files.writeString(Paths.get(args[1]),Json.write(ProjectStore.map("id",p.id(),"token",token)));
  System.out.println("PASS one-use connection prepared without permanent password storage");
 }
}

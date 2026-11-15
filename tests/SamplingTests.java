import java.util.*;import java.util.concurrent.atomic.*;import java.nio.file.*;import ru.bccontrol.sedonastudio.protocol.*;import ru.bccontrol.sedonastudio.storage.*;
public class SamplingTests {
 static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
 public static void main(String[] args)throws Exception{
  List<String> read=new ArrayList<>();SampleBatch.Result r=SampleBatch.read(List.of("good1","bad","good2"),id->{read.add(id);if(id.equals("bad"))throw new java.io.IOException("Temporary read failure");return Map.of("out",id);},()->true);check(r.values().keySet().equals(Set.of("good1","good2"))&&r.errors().keySet().equals(Set.of("bad")),"One failed block must not stop others");check(read.size()==3&&r.times().size()==2&&r.times().get("good1")<=r.times().get("good2"),"Successful timestamps reflect actual reads");
  AtomicBoolean connected=new AtomicBoolean(true);read.clear();r=SampleBatch.read(List.of("one","two"),id->{read.add(id);connected.set(false);return Map.of("out","1");},connected::get);check(read.size()==1&&r.values().size()==1,"Loss of connection stops further reads without reconnect");
  r=SampleBatch.read(List.of("bad"),id->{throw new IllegalArgumentException("Removed component");},()->true);check(r.values().isEmpty()&&r.errors().size()==1,"All-failed batch reports failure");
  Path logs=Files.createTempDirectory("studio-log-");SessionLog.append(logs,"Read failed\nUI still works");check(Files.readString(logs.resolve("studio.log")).contains("Read failed UI still works"),"Persistent bounded diagnostics");Files.writeString(logs.resolve("studio.log"),"x".repeat(1048600));SessionLog.append(logs,"rotated");check(Files.exists(logs.resolve("studio.previous.log"))&&Files.readString(logs.resolve("studio.log")).contains("rotated"),"Log rotation");Path invalid=logs.resolve("not-directory");Files.writeString(invalid,"file");SessionLog.append(invalid,"Must not throw");
  System.out.println("PASS sampling: partial successes survive component failure, actual timestamps, connection-loss stop, all-failed result; diagnostic logs persist/rotate and logging errors cannot stop UI");
 }
}

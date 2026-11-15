import java.nio.file.*;
import java.util.*;
import java.util.prefs.Preferences;
import ru.bccontrol.sedonastudio.storage.Json;

/** Explicit local setup for this workstation; never copied into the application runtime. */
public final class ConfigureLocalIcons {
 public static void main(String[] args)throws Exception{
  Path icons=Path.of(args[0]).toAbsolutePath(),oldSdk=Path.of(args[1]).toAbsolutePath(),backup=Path.of(args[2]);
  if(!Files.isRegularFile(icons))throw new IllegalArgumentException("Local image archive missing");
  Preferences prefs=Preferences.userRoot().node("ru/bccontrol/sedona-studio-windows");
  Map<String,Object> previous=new LinkedHashMap<>();
  for(String key:List.of("iconsPath","sdkPath","typeIcons","canvasNotes"))previous.put(key,prefs.get(key,null));
  Files.createDirectories(backup.getParent());Files.writeString(backup,Json.write(previous));
  if(prefs.get("iconsPath","").isBlank())prefs.put("iconsPath",icons.toString());
  // Preserve the existing working SDK and remembered kit folders when updating the executable.
  if(prefs.get("sdkPath","").isBlank() && Files.isRegularFile(oldSdk.resolve("lib/sedonac.jar")))prefs.put("sdkPath",oldSdk.toString());
  prefs.flush();
  System.out.println("PASS local images configured; working SDK preserved; previous preferences saved to "+backup);
 }
}

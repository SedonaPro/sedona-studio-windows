import java.nio.file.*;import java.util.*;import java.io.*;import java.util.zip.*;
import sedona.*;import sedona.manifest.*;import sedona.xml.*;
import ru.bccontrol.sedonastudio.storage.*;import ru.bccontrol.sedonastudio.protocol.*;
public class SessionManifestProbe{
 public static void main(String[] args)throws Exception{
  Path home=Paths.get(args[0]);Files.createDirectories(home.resolve("lib"));Files.writeString(home.resolve("lib/sedona.properties"),"# isolated session\n");System.setProperty("sedona.home",home.toString());
  byte[] archive;try(InputStream in=LocalCatalog.class.getResourceAsStream("/catalog-manifests.zip")){archive=in.readAllBytes();}
  List<KitPart> requested=new ArrayList<>();try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(archive))){ZipEntry e;while((e=z.getNextEntry())!=null){byte[] xml=z.readAllBytes();KitManifest m=KitManifest.fromXml(XParser.make("test",new ByteArrayInputStream(xml)).parse());if(Set.of("sys","math","types").contains(m.name))requested.add(m.part());}}
  int[] fetch={0};KitPart[] parts=requested.toArray(new KitPart[0]);
  ManifestArchive.loadSchema(parts,uri->{fetch[0]++;return archive;});
  if(fetch[0]!=1)throw new AssertionError("Expected device archive fetch into empty session cache");
  long cached;try(var walk=Files.walk(home.resolve("manifests"))){cached=walk.filter(p->p.toString().endsWith(".xml")).count();}
  if(cached!=3)throw new AssertionError("Extra kits from device archive leaked into session: "+cached);
  ManifestArchive.loadSchema(parts,uri->{throw new AssertionError("Unnecessary re-download");});
  ManifestArchive.loadSchema(parts,uri->{fetch[0]++;return archive;},true);if(fetch[0]!=2)throw new AssertionError("Explicit re-download skipped");
  if(LocalCatalog.exact(requested.stream().map(Object::toString).toList()).types().isEmpty())throw new AssertionError("Offline exact session palette");
  System.out.println("PASS session cache: missing manifests fetched from device, only exact 3 required parts saved, subsequent offline schema has no fetch");
 }
}

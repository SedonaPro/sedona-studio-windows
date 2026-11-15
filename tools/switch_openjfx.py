from pathlib import Path
r=Path('sedona-studio');f=r/'build.py';s=f.read_text(encoding='utf-8').replace("str(jre/'lib/ext/jfxrt.jar')","str(r/'lib/javafx/*')").replace("args=['--release','8'","args=['--release','17'")
a=s.index("(out/'start.sh').write_text(");b=s.index("\nprint('BUILT'",a);s=s[:a]+"shutil.copy2(r/'start.sh',out/'start.sh')"+s[b:];s=s.replace('-jar sedona-studio.jar\\n','--add-modules javafx.controls,javafx.swing -jar sedona-studio.jar\\n');f.write_text(s,encoding='utf-8')
f=r/'test.py';s=f.read_text(encoding='utf-8').replace("['javac','--release','8'","['javac','--release','17'");f.write_text(s,encoding='utf-8')
f=r/'src/main/java/ru/bccontrol/sedonastudio/Launcher.java';s=f.read_text(encoding='utf-8');a=s.index('    String data =');b=s.index('    System.setProperty("studio.data"',a);s=s[:a]+'''    String local=System.getenv("LOCALAPPDATA");
    String defaultData=local!=null?java.nio.file.Paths.get(local,"BCControl","SedonaStudio").toString():java.nio.file.Paths.get(System.getProperty("user.home"),".local","share","sedona-studio").toString();
    String data=System.getProperty("studio.data",System.getenv("SEDONA_STUDIO_DATA")==null?defaultData:System.getenv("SEDONA_STUDIO_DATA"));
'''+s[b:];f.write_text(s,encoding='utf-8')

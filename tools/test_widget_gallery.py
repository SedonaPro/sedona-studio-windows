from pathlib import Path
import subprocess,os
r=Path(__file__).resolve().parents[1];out=r/'build/panel/gallery';out.mkdir(parents=True,exist_ok=True)
cp=os.pathsep.join(str(p) for p in (r/'build/panel/artifacts').glob('*.jar'))
fx=os.pathsep.join(str(p) for p in (r/'lib/javafx').glob('*.jar'))
subprocess.run(['javac','--release','17','-encoding','UTF-8','-cp',cp+os.pathsep+fx,'-d',str(out),str(r/'tests/panel/WidgetGalleryTest.java')],check=True)
java=r/'dist/SedonaStudio/runtime/bin/java.exe'
p=subprocess.run([str(java),'--add-modules','javafx.controls,javafx.swing','-cp',str(out)+os.pathsep+cp,'ru.bccontrol.panel.widgets.WidgetGalleryTest',str(out/'scenarios.png')],capture_output=True,text=True,encoding='utf-8',timeout=40)
(out/'gallery.log').write_text(p.stdout+p.stderr,encoding='utf-8');print(p.stdout+p.stderr);p.check_returncode()
assert 'PASS gallery' in p.stdout

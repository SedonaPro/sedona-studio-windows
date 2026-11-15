from pathlib import Path
import subprocess,os
r=Path(__file__).resolve().parents[1];build=r/'build/panel/tests';build.mkdir(parents=True,exist_ok=True)
cp=os.pathsep.join(str(r/'build/panel/artifacts'/name) for name in ['studio-panel-api.jar','studio-panel-widgets.jar','studio-panel-demo.jar'])
p=subprocess.run(['javac','--release','17','-encoding','UTF-8','-cp',cp,'-d',str(build),str(r/'tests/panel/PanelTests.java')],capture_output=True,text=True,encoding='utf-8',errors='replace');print(p.stdout+p.stderr);p.check_returncode()
# Bundled runtime when present (Windows release layout), otherwise the JDK on PATH with the JavaFX jars.
runtime=r/'dist/SedonaStudio/runtime/bin'/('java.exe' if os.name=='nt' else 'java')
java=str(runtime) if runtime.exists() else 'java'
fx=[] if runtime.exists() else ['--module-path',str(r/('lib/javafx' if os.name=='nt' else 'lib/javafx-linux'))]
monocle=sorted((r/'lib/test').glob('openjfx-monocle-*.jar'))
# No display (CI, SSH): render offscreen with the Monocle headless toolkit.
if os.name!='nt' and monocle and (not os.environ.get('DISPLAY') or os.environ.get('STUDIO_HEADLESS')):fx+=['--patch-module','javafx.graphics='+str(monocle[-1]),'-Dglass.platform=Monocle','-Dmonocle.platform=Headless','-Dprism.order=sw','-Dheadless.geometry=1920x1200-32','-Djava.awt.headless=true']
for name,cmd in [('contracts',[java,'-cp',str(build)+os.pathsep+cp,'PanelTests']),('demo-ui',[java]+fx+['--add-modules','javafx.controls,javafx.swing','-Dpanel.snapshot='+str(build/'demo.png'),'-jar',str(r/'dist/SedonaStudio/panel-demo/studio-panel-demo.jar'),'--smoke'])]:
 p=subprocess.run(cmd,timeout=40,capture_output=True,text=True,encoding='utf-8',errors='replace');output=p.stdout+p.stderr;(build/(name+'.log')).write_text(output,encoding='utf-8');print(output);p.check_returncode();assert 'PASS' in output and 'AssertionError' not in output

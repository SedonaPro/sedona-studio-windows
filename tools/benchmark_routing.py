from pathlib import Path
import subprocess,os,json
r=Path(__file__).resolve().parents[1];out=r/'build/route-benchmark';out.mkdir(exist_ok=True)
java=r/'dist/SedonaStudio/runtime/bin/java.exe';app=r/'dist/SedonaStudio/sedona-studio.jar';previous=r/'releases/0.2.2/SedonaStudio/app/sedona-studio.jar'
fx=os.pathsep.join(str(p) for p in (r/'lib/javafx').glob('*.jar'))
subprocess.run(['javac','--release','17','-cp',str(app)+os.pathsep+fx,'-d',str(out),str(r/'tests/RoutingBenchmark.java')],check=True)
results={}
for label,jar in [('baseline-0.2.2',previous),('candidate-0.2.3',app)]:
 p=subprocess.run([str(java),'--add-modules','javafx.graphics','-cp',str(out)+os.pathsep+str(jar),'RoutingBenchmark'],capture_output=True,text=True,timeout=50,check=True);results[label]=json.loads(p.stdout);print(label,p.stdout.strip())
results['scope']='Only route computation for 100 independent unobstructed forward links across 200 cards; excludes rendering, mouse, obstacle scenes and low-end hardware.'
(out/'comparison.json').write_text(json.dumps(results,indent=2),encoding='utf-8')

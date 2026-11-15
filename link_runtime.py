from pathlib import Path
import urllib.request,concurrent.futures,hashlib,json,subprocess,shutil,datetime,os
r=Path('sedona-studio').resolve();lib=r/'lib/javafx';lib.mkdir(exist_ok=True)
version='17.0.12';names=['javafx-base','javafx-graphics','javafx-controls','javafx-swing']
def fetch(name):
 file=lib/(name+'-'+version+'-win.jar');url='https://repo.maven.apache.org/maven2/org/openjfx/'+name+'/'+version+'/'+file.name
 if not file.exists():
  with urllib.request.urlopen(url,timeout=45) as response:file.write_bytes(response.read())
 return {'file':file.name,'url':url,'sha256':hashlib.sha256(file.read_bytes()).hexdigest()}
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:metadata=list(pool.map(fetch,names))
(r/'lib/javafx/dependencies.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')
# Keep the previous tested runtime as an intermediate artifact; both paths are owned by this project.
image=r/'dist/SedonaStudio/runtime';previous=r/'build/runtime-java8-stage';
if image.exists() and not (image/'release').exists():
 if r not in image.resolve().parents or r not in previous.resolve().parents or previous.exists():raise RuntimeError('Unsafe runtime archive paths')
 image.rename(previous)
if not image.exists():
 command=['jlink','--module-path',str(lib),'--add-modules','javafx.controls,javafx.swing,java.naming,java.prefs,java.logging,java.management,jdk.unsupported,jdk.crypto.ec','--strip-debug','--no-header-files','--no-man-pages','--compress=2','--output',str(image)]
 result=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',errors='replace');(r/'build/runtime-link.log').write_text(result.stdout+result.stderr,encoding='utf-8');print(result.stdout+result.stderr);result.check_returncode()
print('PASS independent OpenJDK/OpenJFX runtime',image)

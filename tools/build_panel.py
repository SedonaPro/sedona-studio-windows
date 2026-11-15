from pathlib import Path
import os,subprocess,shutil,zipfile,argparse
parser=argparse.ArgumentParser();parser.add_argument('--only',choices=['all','api','panel','demo'],default='all');options=parser.parse_args()
r=Path(__file__).resolve().parents[1];build=r/'build/panel';build.mkdir(parents=True,exist_ok=True);out=build/'artifacts';out.mkdir(exist_ok=True)
fx=[str(p) for p in (r/('lib/javafx' if os.name=='nt' else 'lib/javafx-linux')).glob('*.jar')]
if not fx:raise RuntimeError('Local JavaFX dependency missing')
artifacts=[]
for folder,name,dependencies in [('api','studio-panel-api.jar',[]),('panel','studio-panel-widgets.jar',['studio-panel-api.jar']),('demo','studio-panel-demo.jar',['studio-panel-api.jar','studio-panel-widgets.jar'])]:
 if options.only not in ('all',folder):continue
 for dependency in dependencies:
  if not (out/dependency).is_file():raise RuntimeError('Build required dependency first: '+dependency)
 classes=build/(folder+'-classes')
 if classes.resolve().parent!=build.resolve() or classes.is_symlink():raise RuntimeError('Unsafe build path')
 if classes.exists():shutil.rmtree(classes)
 classes.mkdir()
 args=['--release','17','-encoding','UTF-8','-cp',os.pathsep.join(fx+[str(out/p) for p in dependencies]),'-d',str(classes)]+[str(p) for p in (r/'modules'/('panel-api' if folder=='api' else 'panel-widgets' if folder=='panel' else 'panel-demo')/'src/main/java').rglob('*.java')]
 argfile=build/(folder+'.args');argfile.write_text('\n'.join('"'+s.replace('\\','/')+'"' for s in args),encoding='utf-8');p=subprocess.run(['javac','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace');print(p.stdout+p.stderr);p.check_returncode()
 resources=r/'modules'/('panel-api' if folder=='api' else 'panel-widgets' if folder=='panel' else 'panel-demo')/'src/main/resources'
 if resources.exists():shutil.copytree(resources,classes,dirs_exist_ok=True)
 manifest='Manifest-Version: 1.0\nImplementation-Version: '+('1.0.0' if folder=='api' else '0.1.0')+'\n'
 if folder=='demo':manifest+='Main-Class: ru.bccontrol.panel.demo.DemoLauncher\nClass-Path: studio-panel-api.jar studio-panel-widgets.jar\n'
 with zipfile.ZipFile(out/name,'w',zipfile.ZIP_DEFLATED) as z:
  z.writestr('META-INF/MANIFEST.MF',manifest+'\n')
  for p in classes.rglob('*'):
   if p.is_file():
    if p.suffix=='.class' and any(v in p.read_bytes() for v in [b'ru/bccontrol/sedonastudio',b'javax/baja/',b'com/tridium/',b'sedona/']):raise RuntimeError('Forbidden host dependency: '+str(p))
    z.write(p,p.relative_to(classes))
 artifacts.append(out/name)
print('PASS internal module build: '+options.only+'; API 1.0.0, widgets/demo 0.1.0; no editor/Sedona/MCS dependencies')

# Publish internal module artifacts for the common application build and development demo.
(r/'modules').mkdir(exist_ok=True);(r/'lib').mkdir(exist_ok=True)
module_out=r/'dist/SedonaStudio/modules';module_out.mkdir(parents=True,exist_ok=True)
demo_out=r/'dist/SedonaStudio/panel-demo';demo_out.mkdir(parents=True,exist_ok=True)
for source,target in [(out/'studio-panel-api.jar',r/'lib/studio-panel-api.jar'),(out/'studio-panel-widgets.jar',r/'modules/studio-panel-widgets.jar'),(out/'studio-panel-widgets.jar',module_out/'studio-panel-widgets.jar')]:
 if source.exists():shutil.copy2(source,target)
for file in out.glob('*.jar'):shutil.copy2(file,demo_out/file.name)

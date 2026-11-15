"""Install the verified local canvas/widgets update; preserve the published archives."""
from pathlib import Path
import datetime,shutil,zipfile,hashlib,json
r=Path(__file__).resolve().parents[1]
log=(r/'build/scenario-widgets-check.log').read_text(encoding='utf-8')
assert 'PASS independent panel contracts' in log and 'PASS independent panel demo' in log
assert 'AssertionError' not in log and 'Traceback' not in log
image=r/'releases/0.7.0/SedonaStudioWindows/app'
files=[('dist/SedonaStudio/sedona-studio.jar','sedona-studio.jar'),
       ('dist/SedonaStudio/modules/studio-panel-widgets.jar','modules/studio-panel-widgets.jar')]
for path in (r/'dist/SedonaStudio/panel-demo').glob('*.jar'):
 files.append((str(path.relative_to(r)), 'panel-demo/'+path.name))
for path in (r/'lang').glob('*.properties'):files.append((str(path.relative_to(r)),'lang/'+path.name))
stamp=datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
backup=r/'checkpoints'/('scenario-widgets-'+stamp+'.zip')
with zipfile.ZipFile(backup,'w',zipfile.ZIP_DEFLATED) as z:
 for source,target in files:
  old=image/target
  if old.is_file():z.write(old,'before/'+target)
 for directory in ['src','modules','lang','tests']:
  for path in (r/directory).rglob('*'):
   if path.is_file() and path.suffix in ['.java','.properties','.css','.png']:z.write(path,path.relative_to(r))
 z.write(r/'build/scenario-widgets-check.log','checks.log')
 z.write(r/'build/panel/tests/demo.png','widgets.png')
hashes={}
for source,target in files:
 dest=image/target;dest.parent.mkdir(parents=True,exist_ok=True)
 shutil.copy2(r/source,dest)
 hashes[target]=hashlib.sha256(dest.read_bytes()).hexdigest()
receipt={'feature':'Ten scenario widget faces with previews',
         'backup':str(backup),'checks':str(r/'build/scenario-widgets-check.log'),'files':hashes,
         'publishedArchivesUnchanged':True,'requiresRestart':True}
(r/'build/scenario-widgets-installed.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8')
print('PASS verified local update installed; restart required; backup:',backup)


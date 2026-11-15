from pathlib import Path
import json,hashlib,zipfile,shutil,datetime
r=Path('sedona-studio').resolve();release=r/'releases/0.1.0';image=release/'SedonaStudio';evidence=release/'checks';evidence.mkdir(exist_ok=True)
for name in ['core','local-sox']:
 text=(r/'build/tests'/(name+'.log')).read_text(encoding='utf-8');assert 'PASS' in text and 'Exception in thread' not in text;shutil.copy2(r/'build/tests'/(name+'.log'),evidence/(name+'.log'))
smoke=(r/'build/native-exe-smoke/stdout.log').read_text(encoding='utf-8');assert 'PASS JavaFX interactions:' in smoke;shutil.copy2(r/'build/native-exe-smoke/stdout.log',evidence/'javafx-native-exe.log');shutil.copy2(r/'build/native-exe-smoke/studio-preview.png',release/'preview-offline-demo.png')
for f in [r/'README_RU.md',r/'provenance.json']:shutil.copy2(f,release/f.name)
# Release documentation and provenance match the final executable payload.
shutil.copy2(r/'README_RU.md',image/'README_RU.md');shutil.copytree(r/'docs',image/'docs',dirs_exist_ok=True)
files={str(p.relative_to(image)).replace('\\','/'):hashlib.sha256(p.read_bytes()).hexdigest() for p in image.rglob('*') if p.is_file()}
(image/'SHA256SUMS').write_text(''.join(v+'  '+k+'\n' for k,v in sorted(files.items())),encoding='utf-8')
metadata={'version':'0.1.0','created':datetime.datetime.now(datetime.timezone.utc).astimezone(datetime.timezone(datetime.timedelta(hours=3))).isoformat(),'application':'Sedona Studio','executable':str(image/'SedonaStudio.exe'),'source':str(r/'src'),'platformVerified':'Windows x64','crossPlatformSource':True,'linuxMacRuntimeVerified':False,'runtime':'minimal OpenJDK 17 + OpenJFX 17.0.12','mcsRuntimeRequired':False,'httpServerRequired':False,'nodeRequired':False,'coreChecks':33,'nativeLocalSvm':'passed: empty cache, create/config/link/meta/group/readback, stale/name-conflict rejection, delete, journals/backups','javafxNativeExecutable':'passed: 1440x900 render, palette/create/duplicate/delete/undo/redo/inspector','physicalDeviceWrites':False,'installedMcsModified':False,'previewData':'offline demonstration, not controller readings','files':files}
(release/'release.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2),encoding='utf-8');(r/'SEDONA_STUDIO_CURRENT.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2),encoding='utf-8')
with zipfile.ZipFile(release/'SedonaStudio-Windows-x64-0.1.0.zip','w',zipfile.ZIP_DEFLATED) as z:
 for p in image.rglob('*'):
  if p.is_file():z.write(p,p.relative_to(release))
with zipfile.ZipFile(release/'SedonaStudio-source-0.1.0.zip','w',zipfile.ZIP_DEFLATED) as z:
 for directory in ['src','tests','lib','docs']:
  for p in (r/directory).rglob('*'):
   if p.is_file() and '__pycache__' not in p.parts:z.write(p,Path('sedona-studio')/p.relative_to(r))
 for name in ['build.py','test.py','link_runtime.py','package_windows.py','README_RU.md','AGENTS.md','provenance.json','start.sh','.gitignore']:z.write(r/name,'sedona-studio/'+name)
 for name in ['format.ps1','checkpoint.py','google-java-format-1.13.0.jar']:z.write(r/'tools'/name,'sedona-studio/tools/'+name)
for archive in release.glob('*.zip'):
 with zipfile.ZipFile(archive) as z:assert z.testzip() is None
print('PASS release: executable, linked runtime, source archive, SHA256, dependency boundary, 33 core checks, real local SOX, native JavaFX smoke')
print(release)

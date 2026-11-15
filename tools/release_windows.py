from pathlib import Path
import json,hashlib,zipfile,shutil,datetime
r=Path(__file__).resolve().parents[1];release=r/'releases/0.7.0';image=release/'SedonaStudioWindows'
accept=json.loads((r/'build/native-windows/acceptance.json').read_text(encoding='utf-8'));assert set(accept)=={'smoke','simulator','native-kit','annotations'} and all(x['passed'] for x in accept.values())
for name,marker in [('kit-workshop','PASS kit workshop 12'),('kit-workshop-ui','PASS kit workshop UI'),('simulator-ui','PASS simulator:'),('live-ui','PASS live canvas'),('local-sox','PASS local native SVM'),('app-files','PASS app files 40'),('toolchain','PASS toolchain 19'),('deferred-edits','PASS deferred edits 6'),('annotations','PASS annotations 19'),('annotations-ui','PASS annotation UI:')]:
 log=r/'build/tests'/(name+'.log');text=log.read_text(encoding='utf-8');assert marker in text;shutil.copy2(log,release/log.name)
for name in ['smoke.log','simulator.log','native-kit.log','smoke.png','simulator.png','native-kit.png.kit.png','annotations.log','annotations.png','fixture-update-evidence.json']:shutil.copy2(r/'build/native-windows'/name,release/name)
origin=json.loads((r/'upstream/snapshot.json').read_text(encoding='utf-8'))
meta={'version':'0.7.0','product':'Sedona Studio Windows','created':datetime.datetime.now().astimezone().isoformat(),'project':str(r),'source':origin['source'],'upstreamCommit':origin['commit'],'upstreamDirtyIncluded':origin['dirty'],'upstreamArchiveSha256':origin['archiveSha256'],'dataFolder':'%LOCALAPPDATA%/BCControl/SedonaStudioWindows','preferencesNode':'ru/bccontrol/sedona-studio-windows','executable':str(image/'SedonaStudioWindows.exe'),'acceptance':accept,'limitations':['Local native C compilation targets the bundled classic Win32 ABI (refSize=4); other platforms need their own toolchain','Bundled classic Windows SDK has standard kits; other diagrams require their exact kit builds','Classic Windows VM may listen on all interfaces; simulator uses a per-run random password'],'debianModified':False,'previousWindowsProjectModified':False,'physicalDevicesTouched':False,'features':['Local Sedona and Win32 C kit build/test from workshop','Multiple native kits in versioned VM with rollback','Nonmodal deferred local edits during device operations','Local original type icons with built-in fallbacks','Independent instance markers and comment/type description','Attached coloured notes with copy, undo, search and readback preservation'],'cCompiler':json.loads((r/'lib/c-toolchain/llvm-dependency.json').read_text(encoding='utf-8'))}
(release/'release.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding='utf-8')
for kind in ['Windows-x64','source']:
 file=release/('SedonaStudioWindows-'+kind+'-0.7.0.zip')
 with zipfile.ZipFile(file,'w',zipfile.ZIP_DEFLATED) as z:
  if kind=='Windows-x64':
   for p in image.rglob('*'):
    if p.is_file():z.write(p,p.relative_to(release))
  else:
   for directory in ['src','tests','tools','lib','docs','modules','lang','sdk','upstream']:
    for p in (r/directory).rglob('*'):
     if p.is_file() and '__pycache__' not in p.parts and not ('c-toolchain' in p.parts and ('zig' in str(p).lower() or p.suffix=='.zip' or p.name=='dependencies.json')):z.write(p,p.relative_to(r))
   for name in ['AGENTS.md','README_RU.md','README_WINDOWS_RU.md','build.py','test.py','package_windows.py','link_runtime.py','provenance.json','Start-SedonaStudio.cmd','Start-SedonaStudio.ps1','Start-Panel-Demo.cmd','start.sh','studio.sh','.gitignore']:z.write(r/name,name)
 with zipfile.ZipFile(file) as z:assert z.testzip() is None
(release/'SHA256SUMS.txt').write_text('\n'.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name for p in release.glob('*.zip'))+'\n',encoding='utf-8')
current=r/'SEDONA_STUDIO_CURRENT.json';shutil.copy2(current,r/'upstream/previous-current-before-0.7.0.json');pending=current.with_suffix('.pending');pending.write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding='utf-8');pending.replace(current)
print('PASS independent Windows 0.7.0 released; source/runtime archives verified; separate launcher ready')

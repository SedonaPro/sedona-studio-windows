from pathlib import Path
import zipfile,datetime,json,hashlib
r=Path(__file__).resolve().parents[1];out=r/'checkpoints'/(datetime.datetime.now().strftime('%Y%m%d-%H%M%S')+'-checkpoint.zip')
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
 for directory in ['src','tests','tools','lib','docs','modules','lang','upstream','sdk']:
  for p in (r/directory).rglob('*'):
   if p.is_file() and not ('c-toolchain' in p.parts and ('zig' in str(p).lower() or p.suffix=='.zip' or p.name=='dependencies.json')):z.write(p,p.relative_to(r))
 for p in (r/'build/tests').glob('*.log'):z.write(p,p.relative_to(r))
 for p in [r/'build.py',r/'package_windows.py',r/'link_runtime.py',r/'test.py',r/'provenance.json',r/'AGENTS.md',r/'README_RU.md',r/'README_WINDOWS_RU.md',r/'Start-Panel-Demo.cmd',r/'Start-SedonaStudio.cmd',r/'Start-SedonaStudio.ps1',r/'SEDONA_STUDIO_CURRENT.json',r/'dist/SedonaStudio/sedona-studio.jar']:z.write(p,p.relative_to(r))
print(out)

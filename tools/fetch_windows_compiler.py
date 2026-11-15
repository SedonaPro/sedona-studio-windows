"""Restore the pinned portable C toolchain used by the Windows kit workshop."""
from pathlib import Path
import urllib.request,hashlib,zipfile,json
root=Path(__file__).resolve().parents[1]/'lib/c-toolchain'
root.mkdir(parents=True,exist_ok=True)
name='llvm-mingw-20240619-ucrt-x86_64'
url='https://github.com/mstorsjo/llvm-mingw/releases/download/20240619/'+name+'.zip'
expected='810703594a7e3eea03385b5329c7ea3bd65f5e496b44cf1b68c17ff436d265e7'
archive=root/(name+'.zip')
if not archive.exists():urllib.request.urlretrieve(url,archive)
actual=hashlib.sha256(archive.read_bytes()).hexdigest()
if actual!=expected:raise RuntimeError('Compiler archive checksum mismatch')
with zipfile.ZipFile(archive) as z:
 for member in z.infolist():
  target=(root/member.filename).resolve()
  if not target.is_relative_to(root.resolve()):raise RuntimeError('Unsafe archive path')
 z.extractall(root)
(root/'llvm-dependency.json').write_text(json.dumps({'url':url,'sha256':actual,'tag':'20240619'},indent=2),encoding='utf-8')
print('PASS pinned Windows C compiler restored')

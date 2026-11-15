from pathlib import Path
import subprocess,shutil,os,json,hashlib,urllib.request
from PIL import Image,ImageDraw
r=Path('sedona-studio').resolve();resources=r/'src/main/resources'
im=Image.new('RGBA',(256,256),'#25394b');draw=ImageDraw.Draw(im);draw.line((67,70,185,128,67,186),fill='#61c3bb',width=12)
for x,y in [(67,70),(185,128),(67,186)]:draw.rounded_rectangle((x-27,y-23,x+27,y+23),radius=9,fill='#f4f8fb',outline='#61c3bb',width=3)
im.save(resources/'icon.png');im.save(r/'docs/studio.ico',sizes=[(16,16),(32,32),(48,48),(128,128),(256,256)])
p=r/'src/main/java/ru/bccontrol/sedonastudio/ui/StudioApp.java';s=p.read_text(encoding='utf-8').replace('    this.stage = stage;','    this.stage = stage;\n    stage.getIcons().add(new javafx.scene.image.Image(getClass().getResourceAsStream("/icon.png")));');p.write_text(s,encoding='utf-8')
p=r/'test.py';s=p.read_text(encoding='utf-8').replace(" if cmd[0]=='java':cmd.insert(1,'-Dfile.encoding=UTF-8')", " if cmd[0]=='java':\n  runtime=r/'dist/SedonaStudio/runtime/bin'/('java.exe' if os.name=='nt' else 'java')\n  if runtime.exists():cmd[0]=str(runtime)\n  cmd.insert(1,'-Dfile.encoding=UTF-8')");p.write_text(s,encoding='utf-8')
with urllib.request.urlopen('https://openjdk.org/legal/gplv2+ce.html',timeout=20) as response:(r/'docs/OPENJFX-LICENSE.html').write_bytes(response.read())
p=r/'docs/THIRD_PARTY.txt';s=p.read_text(encoding='utf-8').replace('Windows Java runtime / JavaFX: copied as a generic runtime distribution; upstream COPYRIGHT, LICENSE, legal/ and THIRDPARTYLICENSEREADME files remain inside runtime/.','Windows Java runtime: minimal OpenJDK 17 image created with jlink; upstream notices remain in runtime/legal. JavaFX: OpenJFX 17.0.12, GPLv2 with Classpath Exception; see OPENJFX-LICENSE.html and lib/javafx/dependencies.json for pinned public Maven artifacts. The previous Java8 bootstrap runtime is not shipped.');p.write_text(s,encoding='utf-8')
p=r/'README_RU.md';s=p.read_text(encoding='utf-8').replace('Открыть `dist/SedonaStudio/Start.cmd`.','Открыть `releases/0.1.0/SedonaStudio/SedonaStudio.exe` или `dist/SedonaStudio/Start.cmd`.').replace('при прямом запуске JAR без параметров — `~/.sedona-studio`.','при прямом запуске JAR используются те же пользовательские пути.').replace('При прямом запуске JAR без параметров — `~/.sedona-studio`.','При прямом запуске JAR используются те же пользовательские пути.');a=s.index('Для сборки нужен javac 17');b=s.index('\nИсходный код использует переносимые',a);s=s[:a]+'''Для сборки нужен OpenJDK 17. Зависимости OpenJFX 17.0.12 закреплены в lib/javafx/dependencies.json; `python -X utf8 link_runtime.py` получает публичные Windows JAR и создаёт минимальный runtime через jlink. `python -X utf8 build.py` собирает приложение с target --release 17. `python -X utf8 package_windows.py` создаёт папку с нативным EXE через jpackage, без установки MCS или системной Java. Исходники обычной библиотеки Sedona не менялись. Классы MCS/Baja/WireSheet в приложении и Sedona dependency запрещены проверкой сборки.
''' +s[b:];p.write_text(s,encoding='utf-8')
(r/'package_windows.py').write_text('''from pathlib import Path
import subprocess,shutil,json,datetime
r=Path(__file__).resolve().parent;work=r/'build/package-input';work.mkdir(exist_ok=True);(work/'lib').mkdir(exist_ok=True)
for source,target in [(r/'dist/SedonaStudio/sedona-studio.jar',work/'sedona-studio.jar'),(r/'lib/sedona.jar',work/'lib/sedona.jar')]:shutil.copy2(source,target)
destination=r/'releases/0.1.0';destination.mkdir(parents=True,exist_ok=True)
if (destination/'SedonaStudio').exists():raise RuntimeError('Preserve the previous release before packaging a new image')
command=['jpackage','--type','app-image','--name','SedonaStudio','--app-version','0.1.0','--vendor','BCControl','--description','Standalone Sedona diagram editor','--input',str(work),'--main-jar','sedona-studio.jar','--main-class','ru.bccontrol.sedonastudio.Launcher','--runtime-image',str(r/'dist/SedonaStudio/runtime'),'--dest',str(destination),'--icon',str(r/'docs/studio.ico'),'--java-options','--add-modules=javafx.controls,javafx.swing','--java-options','-Xmx768m','--java-options','-Dfile.encoding=UTF-8']
result=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',errors='replace');(r/'build/package.log').write_text(result.stdout+result.stderr,encoding='utf-8');print(result.stdout+result.stderr);result.check_returncode()
image=destination/'SedonaStudio';shutil.copy2(r/'README_RU.md',image/'README_RU.md');shutil.copytree(r/'docs',image/'docs',dirs_exist_ok=True)
print('PASS native app image',image/'SedonaStudio.exe')
''',encoding='utf-8')

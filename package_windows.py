from pathlib import Path
import subprocess,shutil,json,datetime
r=Path(__file__).resolve().parent;work=r/'build/package-input';work.mkdir(exist_ok=True);(work/'lib').mkdir(exist_ok=True)
for source,target in [(r/'dist/SedonaStudio/sedona-studio.jar',work/'sedona-studio.jar'),(r/'lib/sedona.jar',work/'lib/sedona.jar'),(r/'lib/studio-panel-api.jar',work/'lib/studio-panel-api.jar')]:shutil.copy2(source,target)
# jpackage adds every input JAR to the application classpath; plugin JARs must be copied AFTER packaging.
module_input=work/'modules'
if module_input.exists():
 if module_input.resolve().parent != work.resolve() or module_input.is_symlink():raise RuntimeError('Unsafe plugin staging path')
 shutil.rmtree(module_input)
destination=r/'releases/0.7.0';destination.mkdir(parents=True,exist_ok=True)
if (destination/'SedonaStudioWindows').exists():raise RuntimeError('Preserve the previous release before packaging a new image')
command=['jpackage','--type','app-image','--name','SedonaStudioWindows','--app-version','0.7.0','--vendor','BCControl','--description','Standalone Sedona diagram editor','--input',str(work),'--main-jar','sedona-studio.jar','--main-class','ru.bccontrol.sedonastudio.Launcher','--runtime-image',str(r/'dist/SedonaStudio/runtime'),'--dest',str(destination),'--icon',str(r/'docs/studio.ico'),'--java-options','--add-modules=javafx.controls,javafx.swing','--java-options','-Xmx768m','--java-options','-Dfile.encoding=UTF-8']
result=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',errors='replace');(r/'build/package.log').write_text(result.stdout+result.stderr,encoding='utf-8');print(result.stdout+result.stderr);result.check_returncode()
image=destination/'SedonaStudioWindows';(image/'app/modules').mkdir(exist_ok=True)
for module in (r/'modules').glob('*.jar'):shutil.copy2(module,image/'app/modules'/module.name)
shutil.copytree(r/'dist/SedonaStudio/panel-demo',image/'app/panel-demo',dirs_exist_ok=True)
shutil.copytree(r/'dist/SedonaStudio/lang',image/'app/lang',dirs_exist_ok=True)
shutil.copytree(r/'dist/SedonaStudio/sdk',image/'app/sdk',dirs_exist_ok=True)
shutil.copytree(r/'dist/SedonaStudio/c-toolchain',image/'app/c-toolchain',dirs_exist_ok=True)
(image/'Panel-Demo.cmd').write_text('@echo off\ncd /d "%~dp0"\n"runtime\\bin\\javaw.exe" --add-modules javafx.controls,javafx.swing -jar "app\\panel-demo\\studio-panel-demo.jar"\n',encoding='ascii')
config=(image/'app/SedonaStudioWindows.cfg').read_text(encoding='utf-8')
assert not any(('modules' in line or 'panel-demo' in line) for line in config.splitlines() if line.startswith('app.classpath=')), 'Plugin leaked into parent classpath'
shutil.copy2(r/'README_RU.md',image/'README_RU.md');shutil.copy2(r/'README_WINDOWS_RU.md',image/'README_WINDOWS_RU.md');shutil.copytree(r/'docs',image/'docs',dirs_exist_ok=True)
print('PASS native app image',image/'SedonaStudioWindows.exe')

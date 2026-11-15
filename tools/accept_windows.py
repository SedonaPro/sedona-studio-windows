from pathlib import Path
import os,subprocess,json,shutil,datetime
r=Path(__file__).resolve().parents[1];image=r/'releases/0.7.0/SedonaStudioWindows';out=r/'build/native-windows';out.mkdir(exist_ok=True)
assert (image/'app/sdk/bin/svm.exe').is_file()
assert (image/'app/lang/ru.properties').is_file()
assert 'sdk' not in '\n'.join(line for line in (image/'app/SedonaStudioWindows.cfg').read_text().splitlines() if line.startswith('app.classpath='))
sim_projects=sorted((r/'build/tests').glob('sim-*/sim.sedona.json'),key=lambda p:p.stat().st_mtime)
assert sim_projects
native_sdk=out/('native-sdk-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
shutil.copytree(image/'app/sdk',native_sdk)
assert (image/'app/c-toolchain/bin/i686-w64-mingw32-clang.exe').is_file()
results={}
for name,args in [('smoke',['--smoke']),('simulator',['--sim-smoke='+str(sim_projects[-1])]),('native-kit',['--native-kit-smoke='+str(out/'native-kit-sources')]),('annotations',['--annotations-smoke'])]:
 env=os.environ.copy();env.pop('SEDONA_STUDIO_SDK',None)
 env['SEDONA_STUDIO_DATA']=str(out/(name+'-data'))
 env['JAVA_TOOL_OPTIONS']='-Dstudio.lang=ru -Dstudio.smokeOutput="'+str(out/(name+'.png'))+'"'
 if name=='annotations' and os.environ.get('STUDIO_TEST_ICONS'):env['JAVA_TOOL_OPTIONS']+=' -Dstudio.test.icons="'+os.environ['STUDIO_TEST_ICONS']+'"' 
 if name=='native-kit':env['JAVA_TOOL_OPTIONS']+=' -Dstudio.sdk="'+str(native_sdk)+'"'
 p=subprocess.run([str(image/'SedonaStudioWindows.exe'),*args],cwd=image,env=env,timeout=600 if name=='native-kit' else 180,capture_output=True)
 log=(p.stdout+p.stderr).decode('utf-8',errors='replace');(out/(name+'.log')).write_text(log,encoding='utf-8')
 p.check_returncode();assert 'AssertionError' not in log and 'Exception in thread' not in log
 marker='PASS JavaFX interactions' if name=='smoke' else 'PASS native kit workshop UI:' if name=='native-kit' else 'PASS annotation UI:' if name=='annotations' else 'PASS simulator:'
 assert marker in log,log
 results[name]={'passed':True,'marker':marker};print('PASS packaged Windows '+name+'; SDK discovered without external configuration',flush=True)
(out/'acceptance.json').write_text(json.dumps(results,indent=2),encoding='utf-8')

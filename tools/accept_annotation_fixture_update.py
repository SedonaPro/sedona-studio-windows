"""Retest a self-test-only update while retaining proven, byte-identical product classes."""
from pathlib import Path
import zipfile,hashlib,shutil,json,os,subprocess,datetime
r=Path(__file__).resolve().parents[1];image=r/'releases/0.7.0/SedonaStudioWindows';jar=image/'app/sedona-studio.jar';new=r/'dist/SedonaStudio/sedona-studio.jar';out=r/'build/native-windows'
with zipfile.ZipFile(jar) as old,zipfile.ZipFile(new) as current:
 assert set(old.namelist())==set(current.namelist())
 changed=[name for name in old.namelist() if old.read(name)!=current.read(name)]
 assert changed==['ru/bccontrol/sedonastudio/ui/SelfTestAnnotations.class'],changed
results={}
for name,marker in [('smoke','PASS JavaFX interactions'),('simulator','PASS simulator:'),('native-kit','PASS native kit workshop UI:')]:
 log=out/(name+'.log');assert marker in log.read_text(encoding='utf-8') and log.stat().st_mtime>=jar.stat().st_mtime
 results[name]={'passed':True,'marker':marker,'productClassesUnchanged':True}
backup=r/'checkpoints'/('0.7.0-before-fixture-only-'+datetime.datetime.now().strftime('%H%M%S')+'.jar');shutil.copy2(jar,backup);shutil.copy2(new,jar)
env=os.environ.copy();env['SEDONA_STUDIO_DATA']=str(out/'annotations-data');env['JAVA_TOOL_OPTIONS']='-Dstudio.lang=ru -Dstudio.smokeOutput="'+str(out/'annotations.png')+'" -Dstudio.test.icons="'+os.environ['STUDIO_TEST_ICONS']+'"'
p=subprocess.run([str(image/'SedonaStudioWindows.exe'),'--annotations-smoke'],cwd=image,env=env,timeout=180,capture_output=True)
log=(p.stdout+p.stderr).decode('utf-8',errors='replace');(out/'annotations.log').write_text(log,encoding='utf-8');print(log)
p.check_returncode();assert 'PASS annotation UI:' in log and 'AssertionError' not in log and 'Exception in thread' not in log
results['annotations']={'passed':True,'marker':'PASS annotation UI:','isolatedFixture':True}
(out/'acceptance.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
(out/'fixture-update-evidence.json').write_text(json.dumps({'changedJarEntries':changed,'previousJar':str(backup),'newJarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'reusedChecks':['smoke','simulator','native-kit'],'reason':'Only the annotation self-test fixture changed; all product classes and resources are byte-identical to the accepted image.'},indent=2),encoding='utf-8')
print('PASS final annotation fixture; product classes byte-identical to accepted native/simulator image')

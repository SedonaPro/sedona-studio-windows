from pathlib import Path
import subprocess,os,time
r=Path.cwd();root=r/'build'/('view-restart-'+time.strftime('%Y%m%d-%H%M%S'));root.mkdir();cmd=[str(r/'dist/SedonaStudio/runtime/bin/java.exe'),'--add-modules','javafx.controls,javafx.swing','-Dfile.encoding=UTF-8','-Dstudio.root='+str(root),'-Dstudio.smokeOutput='+str(root/'restored.png'),'-jar',str(r/'dist/SedonaStudio/sedona-studio.jar')]
for mode in ['write','read']:
 p=subprocess.run(cmd+['--view-'+mode+'-smoke'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=40,creationflags=0x08000000);output=p.stdout+p.stderr;(root/(mode+'.log')).write_text(output,encoding='utf-8');print(output);p.check_returncode();assert 'PASS view' in output and 'AssertionError' not in output
assert (root/'restored.png').is_file()

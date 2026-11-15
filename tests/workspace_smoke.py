from pathlib import Path
import subprocess,os,json,time
r=Path.cwd();data=r/'build'/('workspace-smoke-'+time.strftime('%Y%m%d-%H%M%S'));data.mkdir()
cmd=[str(r/'dist/SedonaStudio/runtime/bin/java.exe'),'--add-modules','javafx.controls,javafx.swing','-Dfile.encoding=UTF-8','-Dstudio.root='+str(data),'-jar',str(r/'dist/SedonaStudio/sedona-studio.jar'),'--workspace-smoke']
p=subprocess.Popen(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,creationflags=0x08000000)
try:
 output=p.communicate(timeout=45)[0].decode('utf-8',errors='replace');(data/'output.log').write_text(output,encoding='utf-8');print(output);assert p.returncode==0 and 'PASS workspace tabs' in output
finally:
 if p.poll() is None:subprocess.run(['taskkill','/PID',str(p.pid),'/T','/F'],capture_output=True);p.wait(timeout=10)

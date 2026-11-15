from pathlib import Path
import subprocess,hashlib,base64,shutil,os,xml.etree.ElementTree as E

WINDOWS=os.name=='nt'
def sdk():
 # Windows: classic sedonadev tree. Elsewhere: the sibling SedonaPro build (./spro build linux).
 return Path(os.environ.get('SEDONA_TEST_SDK','F:/Sedona/sedonadev-master' if WINDOWS else str(Path(__file__).resolve().parents[2]/'sedonapro/build/home')))
def svm(old):
 if 'SEDONA_TEST_SVM' in os.environ:return Path(os.environ['SEDONA_TEST_SVM'])
 return old/'bin/svm.exe' if WINDOWS else old.parent/'linux-amd64-full-sim/run/svm'
def launch(old,work,log):
 # Loopback-only fixture VM; CREATE_NO_WINDOW keeps Windows consoles from flashing.
 return subprocess.Popen([str(svm(old)),'kits.scode','app.sab'],cwd=work,stdout=log,stderr=subprocess.STDOUT,creationflags=0x08000000 if WINDOWS else 0)

def prepare(root,live=False):
 old=sdk();work=root/'build/local-sox'
 work.mkdir(parents=True,exist_ok=True)
 code=E.Element('sedonaCode',endian='little',blockSize='4',refSize=os.environ.get('SEDONA_TEST_REFSIZE','4' if WINDOWS else '8'),main='sys::Sys.main',debug='false',test='false')
 for name in (['sys','sox','inet','types','math']+(['func'] if live else [])):E.SubElement(code,'depend',on=name+' 1.2')
 E.ElementTree(code).write(work/'kits.xml',encoding='utf-8')
 app=E.Element('sedonaApp');schema=E.SubElement(app,'schema')
 for name in (['sys','sox','inet','types','math']+(['func'] if live else [])):E.SubElement(schema,'kit',name=name)
 tree=E.SubElement(app,'app');E.SubElement(tree,'prop',name='appName',val='LocalJarTest')
 svc=E.SubElement(tree,'comp',name='service',type='sys::Folder')
 E.SubElement(svc,'comp',name='plat',type='sys::PlatformService')
 users=E.SubElement(svc,'comp',name='users',type='sys::UserService')
 user=E.SubElement(users,'comp',name='admin',type='sys::User')
 credential=base64.b64encode(hashlib.sha1(b'admin:localJarTest').digest()).decode()
 for n,v in [('cred',credential),('perm','2147483647'),('prov','255')]:E.SubElement(user,'prop',name=n,val=v)
 sox=E.SubElement(svc,'comp',name='sox',type='sox::SoxService');E.SubElement(sox,'prop',name='port',val='11976')
 if live:
  ramp=E.SubElement(tree,'comp',name='live1',type='func::Ramp')
  E.SubElement(ramp,'prop',name='period',val='1.7')
  E.SubElement(tree,'comp',name='live2',type='math::Add2')
 links=E.SubElement(app,'links')
 if live:E.SubElement(links,'link',{'from':'/live1.out','to':'/live2.in1'})
 E.ElementTree(app).write(work/'app.sax',encoding='utf-8')
 cp=str(old/'lib/sedona.jar')+os.pathsep+str(old/'lib/sedonac.jar')
 for filename in ['kits.xml','app.sax']:
  r=subprocess.run(['java','-Dsedona.home='+str(old),'-cp',cp,'sedonac.Main',str(work/filename)],capture_output=True)
  (work/(filename+'.log')).write_bytes(r.stdout+r.stderr)
  if r.returncode:raise RuntimeError('Local fixture compile failed: '+filename)
 # The private NRE user will find the exact original kit checksums locally.
 return old,work

def start(root):
 old,work=prepare(root)
 output=open(work/'svm.log','wb')
 p=launch(old,work,output)
 return p,output,old

if __name__=='__main__':
 prepare(Path(__file__).resolve().parents[1]);print('PASS local SOX fixture compiled')

from pathlib import Path
import datetime
r=Path('sedona-studio');f=r/'src/main/java/ru/bccontrol/sedonastudio/protocol/SoxDeviceSession.java';s=f.read_text(encoding='utf-8');needle='    if (steps.isEmpty()) return read();';s=s.replace(needle,needle+'''
    for(Project.Node n:after.nodes.values()){
      Project.Node old=before.nodes.get(n.id);
      if(n.id.equals("0")&&old!=null&&!old.name.equals(n.name))throw new IllegalArgumentException("Имя корня не изменяется этим редактором");
      if(old==null||!old.name.equals(n.name))for(Project.Node occupied:before.nodes.values())if(!occupied.id.equals("0")&&!occupied.id.equals(n.id)&&occupied.parent.equals(n.parent)&&occupied.name.equals(n.name))throw new IllegalArgumentException("Имя ещё занято на устройстве: "+n.name+". Переименуйте или удалите прежний блок отдельным применением.");
    }
''',1);f.write_text(s,encoding='utf-8')
f=r/'src/main/java/ru/bccontrol/sedonastudio/model/Project.java';s=f.read_text(encoding='utf-8').replace('if (!names.add(n.parent + "/" + n.name))','if (!n.id.equals("0")&&!names.add(n.parent + "/" + n.name))');f.write_text(s,encoding='utf-8')
f=r/'tests/LocalSoxTest.java';s=f.read_text(encoding='utf-8');needle=' Project changed=created.copy();';assert needle in s;s=s.replace(needle,''' Project collision=created.copy();collision.remove(sum.id);collision.nodes.get(actual.id).name="sum1";boolean nameRejected=false;try{session.apply(created,collision,false,out.resolve("name-collision"));}catch(IllegalArgumentException expected){nameRejected=true;}if(!nameRejected||session.read().wires.size()!=created.wires.size())throw new AssertionError("Name collision must reject before unlink");
 Project changed=created.copy();''',1).replace('stale-write rejection before mutation;','stale-write and occupied-name rejection before mutation;');f.write_text(s,encoding='utf-8')
image=r/'releases/0.1.0/SedonaStudio';target=r/'build'/('native-image-stage-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'));base=r.resolve()
if image.exists():
 if base not in image.resolve().parents or base not in target.resolve().parents:raise RuntimeError('Unsafe native image archive path')
 image.rename(target)

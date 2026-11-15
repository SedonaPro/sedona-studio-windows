from pathlib import Path
import datetime
r=Path('sedona-studio');p=r/'src/main/java/ru/bccontrol/sedonastudio/ui/StudioApp.java';s=p.read_text(encoding='utf-8');s=s.replace('    b.setOnAction(e -> guard(action));','    b.setOnAction(e -> {if(busy.get()){message("Предыдущая операция ещё выполняется");return;}guard(action);});');s=s.replace('    if (log != null)\n      log.appendText', '    if (log != null)\n      log.appendText')
# Bound the interactive log while detailed operation journals remain on disk.
needle='    if (status != null) status.setText(text);';s=s.replace(needle,'    if(log!=null&&log.getLength()>150000)log.deleteText(0,log.getLength()-100000);\n'+needle)
s=s.replace('new Label("Кэш: " + display(n, port.name))','new Label((baseline==null?"Проект: ":"Кэш: ") + display(n, port.name))');s=s.replace('    status = new Label("Автономный редактор Sedona · JavaFX");','    status = new Label("ОФЛАЙН · схема не исполняется · Sedona Studio / JavaFX");')
s=s.replace('  private long clock = System.nanoTime();','  private long clock = System.nanoTime();\n  private final Map<String,Long> lastTraceSample=new HashMap<>();')
s=s.replace('                if (!Double.isFinite(number)) continue;','''                if (!Double.isFinite(number)){entry.getValue().getData().clear();lastTraceSample.remove(entry.getKey());continue;}
                long now=System.nanoTime();Long previous=lastTraceSample.put(entry.getKey(),now);if(previous!=null&&now-previous>2500000000L)entry.getValue().getData().clear();''')
s=s.replace('              } catch (Exception ignored) {\n              }','              } catch (Exception ignored) {entry.getValue().getData().clear();lastTraceSample.remove(entry.getKey());\n              }')
s=s.replace('    traces.clear();\n    chart.getData().clear();','    traces.clear();lastTraceSample.clear();\n    chart.getData().clear();');p.write_text(s,encoding='utf-8')
p=r/'README_RU.md';s=p.read_text(encoding='utf-8').replace('удаление сигналов через контекстное меню, CSV.','удаление сигналов через контекстное меню, CSV. При недопустимом отсчёте или разрыве более 2.5s буфер соответствующей кривой очищается, чтобы не соединять линией интервалы без данных. Это временное ограничение текущего native LineChart; долговременного архива нет. Большие long в графике преобразуются в double; точное значение доступно в инспекторе и JSON.');p.write_text(s,encoding='utf-8')
# Archive only our own first native image before the final build.
image=r/'releases/0.1.0/SedonaStudio';target=r/'build'/('native-image-stage-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
base=r.resolve()
if image.exists():
 if base not in image.resolve().parents or base not in target.resolve().parents:raise RuntimeError('Unsafe native-image archive path')
 image.rename(target)

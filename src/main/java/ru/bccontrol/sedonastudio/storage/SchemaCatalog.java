package ru.bccontrol.sedonastudio.storage;

import java.util.*;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.SecretFields;
import sedona.*;

public final class SchemaCatalog {
  public static List<Project.Node> types(Schema schema) {
    List<Project.Node> result = new ArrayList<>();
    for (Kit kit : schema.kits)
      for (sedona.Type type : kit.types) {
        if (!type.isaComponent()
            || type.isAbstract()
            || (kit.name.equals("sys") && !type.qname.equals("sys::Folder"))
            || (schema.type("sys::Service") != null && type.is(schema.type("sys::Service")))
            || !type.facets.getb("palette", true)
            || type.facets.getb("testonly", false)) continue;
        boolean sensitive = false;
        for (Slot s : type.configProps()) if (SecretFields.matches(s.name)) sensitive = true;
        if (sensitive) continue;
        Project.Node n = new Project.Node();
        n.type = type.qname;
        n.typeIcon = type.facets.gets("niagaraIcon", "");
        n.kitPart = kit.manifest.part().toString();
        for (Slot s : type.slots)
          if (s.isProp() && !s.name.equals("meta") && !SecretFields.matches(s.name)) {
            n.ports.add(Components.port(s));
            if (s.def() != null) n.values.put(s.name, s.def().encodeString());
          }
        result.add(n);
      }
    result.sort(Comparator.comparing(n -> n.type));
    return result;
  }
}

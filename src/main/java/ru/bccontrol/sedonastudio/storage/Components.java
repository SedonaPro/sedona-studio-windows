package ru.bccontrol.sedonastudio.storage;

import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.SecretFields;
import sedona.Component;
import sedona.Int;
import sedona.Slot;
import sedona.Value;

/** Turns Sedona components (read from a device or from an app file) into project blocks. */
public final class Components {
  private Components() {}

  /** A property as a block port. */
  public static Project.Port port(Slot s) {
    Project.Port p = new Project.Port();
    p.id = s.id;
    p.name = s.name;
    p.type = s.type.qname;
    p.readonly = s.facets.getb("readonly", false);
    p.input = !p.readonly && !s.isAsStr();
    p.asStr = s.isAsStr();
    p.textMax = s.facets.geti("max", 0);
    p.config = s.isConfig();
    return p;
  }

  /**
   * A component as a block. Secret fields are left out entirely. The position comes from the "meta"
   * property when it has one, otherwise the blocks are laid out in rows by their index.
   */
  public static Project.Node node(Component c, String parentId, int index) {
    Project.Node n = new Project.Node();
    n.id = Integer.toString(c.id());
    n.name = c.name();
    n.type = c.type.qname;
    n.typeIcon = c.type.facets.gets("niagaraIcon", "");
    n.kitPart = c.type.kit.manifest.part().toString();
    n.parent = parentId;
    n.x = 80 + (index % 4) * 250;
    n.y = 60 + (index / 4) * 200;
    for (Slot s : c.type.slots)
      if (s.isProp() && !SecretFields.matches(s.name)) {
        Value v = c.get(s);
        if (v != null) n.values.put(s.name, v.encodeString());
        if (s.name.equals("meta")) {
          if (v instanceof Int && ((Int) v).val != 0) {
            int meta = ((Int) v).val;
            n.x = ((meta >>> 24) & 255) * 12;
            n.y = ((meta >>> 16) & 255) * 12;
          }
          continue;
        }
        n.ports.add(port(s));
      }
    return n;
  }
}

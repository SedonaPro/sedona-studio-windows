package ru.bccontrol.sedonastudio.storage;

import java.io.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.Tr;

public final class Catalog {
  public static List<Project.Node> bundled() throws IOException {
    String data;
    try (InputStream in = Catalog.class.getResourceAsStream("/catalog.json")) {
      if (in == null) throw new IOException(Tr.of("catalog.none"));
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] b = new byte[4096];
      int n;
      while ((n = in.read(b)) >= 0) out.write(b, 0, n);
      data = new String(out.toByteArray(), "UTF-8");
    }
    Map<String, Object> wrapper = Json.object("{\"items\":" + data + "}");
    List<Project.Node> result = new ArrayList<>();
    for (Object o : (List<?>) wrapper.get("items")) {
      Map<?, ?> m = (Map<?, ?>) o;
      Project.Node node = new Project.Node();
      node.type = (String) m.get("type");
      node.kitPart = (String) m.get("kitPart");
      for (Object v : (List<?>) m.get("ports")) {
        Map<?, ?> a = (Map<?, ?>) v;
        Project.Port p = new Project.Port();
        p.id = ((Number) a.get("id")).intValue();
        p.name = (String) a.get("name");
        p.type = (String) a.get("type");
        p.input = Boolean.TRUE.equals(a.get("input"));
        p.readonly = Boolean.TRUE.equals(a.get("readonly"));
        p.config = Boolean.TRUE.equals(a.get("config"));
        node.ports.add(p);
      }
      ((Map<?, ?>) m.get("defaults")).forEach((k, v) -> node.values.put((String) k, (String) v));
      result.add(node);
    }
    return result;
  }
}

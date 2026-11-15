package ru.bccontrol.sedonastudio.toolchain;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a component type is for, in the words of its author: the "**" comment above the class in the
 * kit's source. Kit files carry no help text, so this works only when the SDK has the sources;
 * otherwise there is simply no text.
 */
public final class KitHelp {
  private static final Pattern NAME =
      Pattern.compile("<sedonaKit[^>]*?\\bname\\s*=\\s*\"([^\"]+)\"");
  private final Map<String, Path> kitFolders = new HashMap<>();
  private final Map<String, String> texts = new HashMap<>();

  /** Looks for kit sources under the given folders (a few levels deep). */
  public KitHelp(Path... roots) {
    for (Path root : roots) {
      if (root == null || !Files.isDirectory(root)) continue;
      try (var files = Files.walk(root, 5)) {
        files
            .filter(p -> p.getFileName().toString().equals("kit.xml"))
            .forEach(
                xml -> {
                  try {
                    if (Files.size(xml) > 512 * 1024) return;
                    Matcher m = NAME.matcher(Files.readString(xml, StandardCharsets.UTF_8));
                    if (m.find()) kitFolders.putIfAbsent(m.group(1), xml.getParent());
                  } catch (Exception unreadable) {
                    // Not a kit we can describe.
                  }
                });
      } catch (Exception unreadable) {
        // No sources here.
      }
    }
  }

  /** The source roots of an SDK: a SedonaPro tree, or a Sedona home that keeps its kit sources. */
  public static KitHelp of(Sdk sdk) {
    if (sdk == null) return new KitHelp();
    if (sdk.tree != null)
      return new KitHelp(
          sdk.tree.resolve("kits"), sdk.tree.resolve("core/kits"), sdk.tree.resolve("platforms"));
    return new KitHelp(sdk.home.resolve("src/kits"), sdk.home.resolve("src"));
  }

  /** The help text of a type ("kit::Type"), or "" when its source is not here. */
  public String text(String qname) {
    return texts.computeIfAbsent(qname, this::read);
  }

  private String read(String qname) {
    int colons = qname.indexOf("::");
    if (colons < 1) return "";
    Path folder = kitFolders.get(qname.substring(0, colons));
    if (folder == null) return "";
    String type = qname.substring(colons + 2);
    try (var files = Files.walk(folder, 3)) {
      Path source =
          files
              .filter(p -> p.getFileName().toString().equals(type + ".sedona"))
              .findFirst()
              .orElse(null);
      if (source == null || Files.size(source) > 1024 * 1024) return "";
      String[] lines = Files.readString(source, StandardCharsets.UTF_8).split("\\R");
      // The "**" block that ends right above the class declaration.
      int declared = -1;
      Pattern declaration =
          Pattern.compile(
              "^\\s*(?:(?:public|abstract|final|internal|@\\S+)\\s+)*class\\s+"
                  + Pattern.quote(type)
                  + "\\b.*");
      for (int i = 0; i < lines.length && declared < 0; i++)
        if (declaration.matcher(lines[i]).matches()) declared = i;
      if (declared < 0) return "";
      int from = declared;
      while (from > 0
          && (lines[from - 1].trim().startsWith("**") || lines[from - 1].trim().startsWith("@")))
        from--;
      StringBuilder text = new StringBuilder();
      for (int i = from; i < declared; i++) {
        String line = lines[i].trim();
        if (!line.startsWith("**")) continue;
        line = line.substring(2).trim();
        if (line.isEmpty()) {
          if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') text.append('\n');
        } else
          text.append(text.length() > 0 && text.charAt(text.length() - 1) != '\n' ? " " : "")
              .append(line);
      }
      String result = text.toString().trim();
      return result.length() > 4000 ? result.substring(0, 4000) + "…" : result;
    } catch (Exception unreadable) {
      return "";
    }
  }
}

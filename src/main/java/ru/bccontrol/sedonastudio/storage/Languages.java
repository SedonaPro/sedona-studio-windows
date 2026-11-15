package ru.bccontrol.sedonastudio.storage;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import ru.bccontrol.sedonastudio.model.Tr;

/**
 * Interface languages of this installation. English is always there (built in); every
 * lang/&lt;code&gt;.properties file next to the application adds or replaces one. With English only
 * there is nothing to choose from.
 */
public final class Languages {
  public record Language(String code, String name) {
    @Override
    public String toString() {
      return name;
    }
  }

  private Languages() {}

  /** Folder with the language files: -Dstudio.lang.dir, otherwise "lang" beside the jar. */
  public static Path directory() {
    String custom = System.getProperty("studio.lang.dir");
    if (custom != null) return Paths.get(custom);
    try {
      Path code =
          Paths.get(Languages.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      return (Files.isDirectory(code) ? code : code.getParent()).resolve("lang");
    } catch (Exception e) {
      return Paths.get("lang");
    }
  }

  public static List<Language> available() {
    List<Language> result = new ArrayList<>();
    result.add(new Language("en", "English"));
    Path dir = directory();
    if (Files.isDirectory(dir))
      try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.properties")) {
        for (Path file : files) {
          String code = file.getFileName().toString().replaceFirst("\\.properties$", "");
          if (!code.matches("[a-z]{2,3}(_[A-Z]{2})?")) continue;
          try {
            String name = read(file).getOrDefault("language.name", code);
            result.removeIf(l -> l.code().equals(code));
            result.add(new Language(code, name));
          } catch (IOException unreadable) {
            // A broken file is not offered.
          }
        }
      } catch (IOException e) {
        // No folder access: English only.
      }
    result.sort(Comparator.comparing(Language::code));
    return result;
  }

  /**
   * The language to start with: an explicit override, then the saved choice, then the system
   * language, then English - each only if this installation has it.
   */
  public static String choose(String override, String saved, Locale system) {
    List<String> codes = available().stream().map(Language::code).toList();
    for (String wanted :
        new String[] {override, saved, system == null ? null : system.getLanguage()})
      if (wanted != null && codes.contains(wanted)) return wanted;
    return "en";
  }

  public static void activate(String code) {
    Path file = directory().resolve(code + ".properties");
    try {
      Tr.use(Files.isRegularFile(file) ? read(file) : Map.of());
    } catch (IOException e) {
      Tr.use(Map.of());
    }
  }

  private static Map<String, String> read(Path file) throws IOException {
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      return Tr.parse(reader);
    }
  }
}

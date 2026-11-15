import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.storage.Languages;

/** Interface languages: English is built in, others come from files, the choice follows what is installed. */
public final class LanguageTests {
  static int passed;
  static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); passed++; }

  public static void main(String[] args) throws Exception {
    Path dir = Files.createTempDirectory("studio-lang");
    System.setProperty("studio.lang.dir", dir.toString());
    // English only: nothing to choose from, whatever was saved or whatever the system speaks.
    check(Languages.available().size() == 1 && Languages.available().get(0).code().equals("en"), "English only");
    check(Languages.choose("ru", "ru", Locale.forLanguageTag("ru")).equals("en"), "Absent language is never chosen");
    Languages.activate("ru");
    check(Tr.of("toolbar.save").equals("Save"), "Absent language falls back to English");
    check(Tr.of("connect.connected", "P", 1234567, 3).equals("Connected · P · 1234567 components · 3 kits"), "Plain substitution, no digit grouping");
    check(Tr.of("no.such.key").equals("no.such.key"), "Unknown key is returned as is");
    // A second language appears as soon as its file does.
    Files.writeString(dir.resolve("ru.properties"), "language.name=Русский\ntoolbar.save=Сохранить\ntemplate.counts=\\ · блоки: {0} / связи: {1}\ninspector.projectValue=Проект:\\u0020\n");
    Files.writeString(dir.resolve("notes.txt"), "ignored");
    Files.writeString(dir.resolve("Bad Name.properties"), "language.name=x");
    List<Languages.Language> two = Languages.available();
    check(two.size() == 2 && two.get(1).code().equals("ru") && two.get(1).name().equals("Русский"), "Language discovered from its file");
    check(Languages.choose(null, null, Locale.forLanguageTag("ru-RU")).equals("ru"), "System language when installed");
    check(Languages.choose(null, "en", Locale.forLanguageTag("ru")).equals("en"), "Saved choice beats the system language");
    check(Languages.choose("ru", "en", Locale.ENGLISH).equals("ru"), "Explicit override beats the saved choice");
    check(Languages.choose(null, "de", Locale.FRENCH).equals("en"), "English when nothing matches");
    Languages.activate("ru");
    check(Tr.of("toolbar.save").equals("Сохранить"), "Active language");
    check(Tr.of("toolbar.open").equals("Open…"), "Missing text falls back to English");
    check(Tr.of("template.counts", 2, 5).equals(" · блоки: 2 / связи: 5") && Tr.of("inspector.projectValue").equals("Проект: "), "Leading and trailing spaces survive");
    Languages.activate("en");
    check(Tr.of("toolbar.save").equals("Save"), "Back to English");
    System.out.println("PASS languages " + passed + " checks: English built in, discovery from files, choice order, fallback, substitution");
  }
}

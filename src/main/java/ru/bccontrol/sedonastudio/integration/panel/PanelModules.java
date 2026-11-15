package ru.bccontrol.sedonastudio.integration.panel;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import javafx.scene.Node;
import javafx.scene.control.Label;
import ru.bccontrol.panel.api.*;
import ru.bccontrol.sedonastudio.model.Tr;

/**
 * Optional runtime module. A missing/incompatible/broken module does not prevent editor startup.
 */
public final class PanelModules implements AutoCloseable {
  private URLClassLoader loader;
  private PanelModule.PanelView view;
  private Node content;
  private String status;

  public static Path defaultDirectory() {
    try {
      return Paths.get(
              PanelModules.class.getProtectionDomain().getCodeSource().getLocation().toURI())
          .getParent()
          .resolve("modules");
    } catch (Exception ex) {
      return Paths.get("modules");
    }
  }

  public PanelModules(Path directory, PanelHost host) {
    try {
      if (!Files.isDirectory(directory))
        throw new IllegalStateException(Tr.of("panel.notInstalled"));
      List<Path> jars;
      try (var files = Files.list(directory)) {
        jars = files.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
      }
      if (jars.isEmpty()) throw new IllegalStateException(Tr.of("panel.notInstalled"));
      for (Path file : jars)
        try (JarFile jar = new JarFile(file.toFile())) {
          if (jar.stream().anyMatch(e -> e.getName().startsWith("ru/bccontrol/panel/api/")))
            throw new IllegalStateException(Tr.of("panel.apiCopy", file.getFileName()));
        }
      loader =
          new URLClassLoader(
              jars.stream()
                  .map(
                      p -> {
                        try {
                          return p.toUri().toURL();
                        } catch (Exception ex) {
                          throw new IllegalStateException(ex);
                        }
                      })
                  .toArray(URL[]::new),
              PanelModule.class.getClassLoader());
      // Modules carry their English texts; this lets them use the editor's language files.
      PanelText.source(
          (key, english) -> {
            String translated = Tr.of(key);
            return translated.equals(key) ? english : translated;
          });
      List<PanelModule> modules =
          ServiceLoader.load(PanelModule.class, loader).stream()
              .map(ServiceLoader.Provider::get)
              .toList();
      if (modules.size() != 1)
        throw new IllegalStateException(Tr.of("panel.oneModule", modules.size()));
      PanelModule module = modules.get(0);
      if (module.apiMajor() != PanelModule.API_MAJOR)
        throw new IllegalStateException(Tr.of("panel.incompatibleApi", module.apiMajor()));
      view = module.create(host);
      content = Objects.requireNonNull(view.content());
      status = "loaded:" + module.id();
    } catch (Exception | LinkageError | ServiceConfigurationError | AssertionError ex) {
      status = Objects.toString(ex.getMessage(), ex.getClass().getSimpleName());
      content = new Label(Tr.of("panel.unavailable", status));
      close();
    }
  }

  public Node content() {
    return content;
  }

  public String status() {
    return status;
  }

  public void close() {
    if (view != null) {
      try {
        view.close();
      } catch (RuntimeException | LinkageError | AssertionError ignored) {
      }
      view = null;
    }
    if (loader != null) {
      try {
        loader.close();
      } catch (Exception ignored) {
      }
      loader = null;
    }
  }
}

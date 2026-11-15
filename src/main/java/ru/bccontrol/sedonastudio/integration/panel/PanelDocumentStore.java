package ru.bccontrol.sedonastudio.integration.panel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import ru.bccontrol.sedonastudio.model.Tr;

/** Host owns session-scoped opaque documents, not panel schema. */
public final class PanelDocumentStore {
  private final Path file;

  public PanelDocumentStore(Path file) {
    this.file = file;
  }

  public String load() throws IOException {
    if (!Files.exists(file)) return "";
    if (Files.size(file) > 262144) throw new IOException(Tr.of("panel.tooLarge"));
    return Files.readString(file);
  }

  public void save(String text) throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 262144) throw new IOException(Tr.of("panel.tooLarge"));
    Files.createDirectories(file.toAbsolutePath().getParent());
    Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "panel-", ".tmp");
    try {
      Files.write(temp, bytes);
      if (Files.exists(file))
        Files.copy(
            file,
            file.resolveSibling(file.getFileName() + ".bak"),
            StandardCopyOption.REPLACE_EXISTING);
      try {
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ex) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}

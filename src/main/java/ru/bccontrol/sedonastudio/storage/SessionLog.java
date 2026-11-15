package ru.bccontrol.sedonastudio.storage;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;

/** Small bounded diagnostic log, independent from the draft and credentials. */
public final class SessionLog {
  public static void append(Path directory, String message) {
    try {
      Files.createDirectories(directory);
      Path log = directory.resolve("studio.log");
      if (Files.exists(log) && Files.size(log) > 1024 * 1024)
        Files.move(
            log, directory.resolve("studio.previous.log"), StandardCopyOption.REPLACE_EXISTING);
      String text = message.replace('\r', ' ').replace('\n', ' ');
      if (text.length() > 4096) text = text.substring(0, 4096);
      Files.writeString(
          log,
          Instant.now() + " " + text + System.lineSeparator(),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException | RuntimeException ignored) {
      /* Logging cannot stop an editor or read cycle. */
    }
  }
}

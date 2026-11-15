package ru.bccontrol.sedonastudio.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Flushes intent and successful steps separately so a process crash leaves an audit trail. */
public final class OperationJournal {
  private final Path file;
  private final List<String> entries = new ArrayList<>();

  public OperationJournal(Path folder) throws IOException {
    file = folder.resolve("journal.log");
    append("BEGIN");
  }

  private void append(String text) throws IOException {
    Files.write(
        file,
        (java.time.Instant.now() + " " + text + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND,
        StandardOpenOption.SYNC);
  }

  public void attempt(String step) throws IOException {
    append("ATTEMPT " + step);
  }

  public void complete(String step) throws IOException {
    append("DONE " + step);
    entries.add(step);
  }

  public List<String> entries() {
    return new ArrayList<>(entries);
  }
}

package ru.bccontrol.sedonastudio.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Tr;

/** Read-only interpretation of operation artifacts. Never infers success from a DONE line alone. */
public final class RecoveryStore {
  public record Operation(
      Path directory, String state, int attempted, int completed, String pending, String details) {
    public String toString() {
      return directory.getFileName() + " · " + state;
    }
  }

  private final Path root;

  public RecoveryStore(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  public Path root() {
    return root;
  }

  public List<Operation> list() throws IOException {
    if (!Files.isDirectory(root)) return List.of();
    List<Operation> out = new ArrayList<>();
    try (var files = Files.list(root)) {
      for (Path dir :
          files
              .filter(p -> Files.isDirectory(p) && !Files.isSymbolicLink(p))
              .sorted(Comparator.reverseOrder())
              .limit(300)
              .toList()) {
        try {
          out.add(inspect(dir));
        } catch (IOException | RuntimeException ex) {
          out.add(
              new Operation(
                  dir, Tr.of("recovery.cannotRead"), 0, 0, "", String.valueOf(ex.getMessage())));
        }
      }
    }
    return List.copyOf(out);
  }

  public static Operation inspect(Path directory) throws IOException {
    String log = read(directory.resolve("journal.log")),
        failure = read(directory.resolve("failure.txt"));
    int attempted = 0, completed = 0;
    String pending = "";
    for (String line : log.split("\\R")) {
      int a = line.indexOf(" ATTEMPT "), d = line.indexOf(" DONE ");
      if (a >= 0) {
        attempted++;
        pending = line.substring(a + 9);
      }
      if (d >= 0) {
        completed++;
        pending = "";
      }
    }
    boolean partial =
        Files.exists(directory.resolve("partial.log"))
            || Files.exists(directory.resolve("failure.txt"));
    boolean hasCompleted = Files.isRegularFile(directory.resolve("completed.log"));
    boolean hasReadback = Files.isRegularFile(directory.resolve("readback.sedona.json"));
    boolean verified =
        !partial
            && hasCompleted
            && hasReadback
            && attempted > 0
            && attempted == completed
            && pending.isEmpty();
    if (verified)
      try {
        ProjectStore.load(directory.resolve("readback.sedona.json"));
      } catch (IOException | RuntimeException ex) {
        verified = false;
        failure += Tr.of("recovery.snapshotDamaged", ex.getMessage());
      }
    String state =
        verified
            ? Tr.of("recovery.verified")
            : partial || attempted > 0 || hasCompleted || hasReadback
                ? Tr.of("recovery.checkRequired")
                : Tr.of("recovery.notStarted");
    String details =
        Tr.of(
            "recovery.summary",
            state,
            attempted,
            completed,
            (pending.isEmpty() ? "" : Tr.of("recovery.lastStep", pending)),
            failure,
            log);
    return new Operation(directory, state, attempted, completed, pending, details);
  }

  private static String read(Path path) throws IOException {
    if (!Files.exists(path)) return "";
    if (Files.isSymbolicLink(path) || Files.size(path) > 1024 * 1024)
      throw new IOException(Tr.of("recovery.journalUnavailable"));
    return Files.readString(path);
  }
}

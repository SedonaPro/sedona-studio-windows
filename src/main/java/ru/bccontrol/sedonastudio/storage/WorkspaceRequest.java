package ru.bccontrol.sedonastudio.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Tr;

/** Bounded, atomic mailbox for isolated session windows. No credentials in messages. */
public final class WorkspaceRequest {
  public record Message(String token, String action, String connect, long pid) {}

  public static String send(Path directory, String action, String connect, long pid)
      throws IOException {
    String token = UUID.randomUUID().toString();
    Path target = directory.resolve("activate-" + token + ".json"),
        temp = Files.createTempFile(directory, "request-", ".tmp");
    try {
      Files.writeString(
          temp,
          Json.write(
              ProjectStore.map(
                  "action",
                  action,
                  "connect",
                  connect,
                  "pid",
                  pid,
                  "created",
                  System.currentTimeMillis())));
      try {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, target);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
    return token;
  }

  public static Optional<Message> consume(Path file, long pid) throws IOException {
    try {
      String name = file.getFileName().toString();
      if (!name.matches("activate-[0-9a-f-]{36}\\.json") || Files.size(file) > 8192)
        throw new IllegalArgumentException(Tr.of("workspace.badRequestSize"));
      Map<String, Object> data = Json.object(Files.readString(file));
      long age = System.currentTimeMillis() - ((Number) data.get("created")).longValue();
      String action = (String) data.getOrDefault("action", "activate"),
          connect = (String) data.getOrDefault("connect", "");
      long target = ((Number) data.getOrDefault("pid", 0L)).longValue();
      if (!Set.of("activate", "close").contains(action)
          || (!connect.isEmpty() && !connect.matches("[0-9a-f-]{36}")))
        throw new IllegalArgumentException(Tr.of("workspace.badAction"));
      Files.delete(file);
      if (age < 0 || age > 120000 || (target != 0 && target != pid)) return Optional.empty();
      return Optional.of(new Message(name.substring(9, 45), action, connect, target));
    } catch (RuntimeException e) {
      Path rejected = file.resolveSibling("rejected-" + UUID.randomUUID() + ".json");
      Files.move(file, rejected);
      throw new IOException(Tr.of("workspace.badRequestSaved", rejected.getFileName()), e);
    }
  }
}

package ru.bccontrol.sedonastudio.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Reads image resources only. Never loads module classes, URLs or arbitrary URI paths. */
public final class IconPack {
  private static final int MAX_IMAGE = 128 * 1024, MAX_TOTAL = 16 * 1024 * 1024, MAX_FILES = 4096;

  private IconPack() {}

  public static String key(String uri) {
    if (uri == null || uri.length() > 512 || !uri.startsWith("module://")) return "";
    String value = uri.substring(9);
    if (!value.matches("[A-Za-z0-9_]+/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_.-]+\\.png")) return "";
    if (value.contains("..")) return "";
    return value;
  }

  public static Map<String, byte[]> read(Path source) throws IOException {
    Map<String, byte[]> result = new LinkedHashMap<>();
    long total = 0;
    if (Files.isRegularFile(source)) {
      if (Files.size(source) > 64L * 1024 * 1024)
        throw new IOException(ru.bccontrol.sedonastudio.model.Tr.of("annotation.packTooLarge"));
      String module =
          source
              .getFileName()
              .toString()
              .replaceFirst("-(?:ux|wb|rt)\\.jar$", "")
              .replaceFirst("\\.(?:jar|zip)$", "");
      try (ZipFile zip = new ZipFile(source.toFile())) {
        var entries = zip.entries();
        int scanned = 0;
        while (entries.hasMoreElements() && result.size() < MAX_FILES && scanned++ < 20000) {
          ZipEntry entry = entries.nextElement();
          String key = key("module://" + module + "/" + entry.getName());
          if (key.isEmpty() || entry.isDirectory() || entry.getSize() > MAX_IMAGE) continue;
          byte[] bytes;
          try (InputStream in = zip.getInputStream(entry)) {
            bytes = in.readNBytes(MAX_IMAGE + 1);
          }
          if (bytes.length > MAX_IMAGE || total + bytes.length > MAX_TOTAL) continue;
          result.put(key, bytes);
          total += bytes.length;
        }
      }
    } else if (Files.isDirectory(source)) {
      try (var files = Files.walk(source, 8)) {
        for (Path file : files.limit(20000).toList()) {
          if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) continue;
          String relative = source.relativize(file).toString().replace('\\', '/');
          String key = key("module://" + relative);
          if (key.isEmpty() || result.size() >= MAX_FILES || Files.size(file) > MAX_IMAGE) continue;
          byte[] bytes;
          try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes(MAX_IMAGE + 1);
          }
          if (bytes.length > MAX_IMAGE || total + bytes.length > MAX_TOTAL) continue;
          result.put(key, bytes);
          total += bytes.length;
        }
      }
    } else throw new FileNotFoundException(source.toString());
    return Collections.unmodifiableMap(result);
  }
}

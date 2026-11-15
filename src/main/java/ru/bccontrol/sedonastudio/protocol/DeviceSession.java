package ru.bccontrol.sedonastudio.protocol;

import java.nio.file.Path;
import java.util.*;
import ru.bccontrol.sedonastudio.model.Project;

/** UI-independent boundary; implementations are owned by one background executor. */
public interface DeviceSession extends AutoCloseable {
  Project connect(String host, int port, String user, char[] password) throws Exception;

  Project read() throws Exception;

  int downloadManifests() throws Exception;

  Map<String, String> sample(String id) throws Exception;

  List<Project.Node> catalog() throws Exception;

  List<String> plan(Project before, Project after);

  Project apply(Project before, Project after, boolean save, Path journal) throws Exception;

  /** A whole file of the device, such as "app.sab" or "kits.scode". */
  byte[] fetch(String uri) throws Exception;

  /**
   * Replaces the application (and the kit code when given) as a whole and restarts the device;
   * returns the project read after the restart. See SoxDeviceSession.deploy.
   */
  Project deploy(byte[] app, byte[] code, Path journal, char[] password) throws Exception;

  boolean connected();

  void close();
}

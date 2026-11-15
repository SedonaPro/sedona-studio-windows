package ru.bccontrol.sedonastudio.ui;

import java.nio.file.*;
import java.util.function.Supplier;
import javafx.scene.control.*;
import javafx.stage.Stage;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.storage.SessionLog;

/** Messages to the user: status line, log tab and error dialogs. */
final class Notices {
  private final Supplier<Stage> owner;
  TextArea log;
  Label status;

  Notices(Supplier<Stage> owner) {
    this.owner = owner;
  }

  /** Run a UI action; a failure becomes an error dialog instead of an uncaught exception. */
  void guard(Runnable action) {
    try {
      action.run();
    } catch (Exception e) {
      error(e);
    }
  }

  void error(Throwable e) {
    message(Tr.of("notice.error", e.getMessage()));
    Alert a = new Alert(Alert.AlertType.ERROR, String.valueOf(e.getMessage()), ButtonType.OK);
    a.initOwner(owner.get());
    a.setHeaderText(Tr.of("notice.notCompleted"));
    a.showAndWait();
  }

  void message(String text) {
    SessionLog.append(Paths.get(System.getProperty("studio.data")), text);
    if (log != null) log.appendText(java.time.LocalTime.now().withNano(0) + "  " + text + "\n");
    if (log != null && log.getLength() > 150000) log.deleteText(0, log.getLength() - 100000);
    if (status != null) status.setText(text);
  }
}

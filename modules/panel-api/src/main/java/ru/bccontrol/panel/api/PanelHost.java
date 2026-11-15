package ru.bccontrol.panel.api;

import static ru.bccontrol.panel.api.PanelData.*;

import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** UI calls and snapshot listeners run on JavaFX thread. Host owns transport/executor. */
public interface PanelHost {
  Snapshot snapshot();

  Subscription subscribe(Consumer<Snapshot> listener);
  /** Replace watched fields for this view; host schedules reads without exposing transport. */
  void watch(Set<Ref> fields);

  CompletionStage<CommandResult> write(Ref target, String literal);

  CompletionStage<CommandResult> invoke(Ref action, String argument);

  String loadDocument() throws Exception;

  void saveDocument(String document) throws Exception;

  interface Subscription extends AutoCloseable {
    void close();
  }
}

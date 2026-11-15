package ru.bccontrol.sedonastudio.integration.panel;

import static ru.bccontrol.panel.api.PanelData.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import ru.bccontrol.panel.api.*;
import ru.bccontrol.sedonastudio.model.Tr;

/** Read-only production adapter. Never exposes a DeviceSession or credentials to API consumers. */
public final class PanelBridge implements PanelHost, AutoCloseable {
  private final Supplier<Snapshot> source;
  private final PanelDocumentStore documents;
  private final Consumer<Throwable> errors;
  private final Set<Consumer<Snapshot>> listeners = new LinkedHashSet<>();
  private Set<Ref> watched = Set.of();
  private boolean closed;

  public PanelBridge(
      Supplier<Snapshot> source, PanelDocumentStore documents, Consumer<Throwable> errors) {
    this.source = source;
    this.documents = documents;
    this.errors = errors;
  }

  public Snapshot snapshot() {
    return source.get();
  }

  public Subscription subscribe(Consumer<Snapshot> listener) {
    if (closed) throw new IllegalStateException("Adapter closed");
    listeners.add(listener);
    try {
      listener.accept(snapshot());
    } catch (RuntimeException | LinkageError | AssertionError ex) {
      listeners.remove(listener);
      throw ex;
    }
    return () -> listeners.remove(listener);
  }

  public void watch(Set<Ref> fields) {
    if (fields.size() > 64) throw new IllegalArgumentException(Tr.of("panel.bindingLimit"));
    watched = Set.copyOf(fields);
  }

  public Set<Ref> watched() {
    return watched;
  }

  public CompletionStage<CommandResult> write(Ref ref, String literal) {
    return CompletableFuture.completedFuture(
        new CommandResult(Outcome.REJECTED, Tr.of("panel.writeDisabled")));
  }

  public CompletionStage<CommandResult> invoke(Ref ref, String argument) {
    return CompletableFuture.completedFuture(
        new CommandResult(Outcome.REJECTED, Tr.of("panel.actionsDisabled")));
  }

  public String loadDocument() throws Exception {
    return documents.load();
  }

  public void saveDocument(String document) throws Exception {
    documents.save(document);
  }

  public void publish() {
    if (closed || listeners.isEmpty()) return;
    Snapshot snapshot = snapshot();
    for (var listener : List.copyOf(listeners))
      try {
        listener.accept(snapshot);
      } catch (RuntimeException | LinkageError | AssertionError ex) {
        listeners.remove(listener);
        errors.accept(ex);
      }
  }

  public void close() {
    closed = true;
    listeners.clear();
    watched = Set.of();
  }
}

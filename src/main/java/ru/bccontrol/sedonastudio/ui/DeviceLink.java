package ru.bccontrol.sedonastudio.ui;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.application.Platform;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.protocol.*;

/**
 * The one device connection of this window: the session, its single worker thread, the busy flag
 * and the last snapshot read from the device. All transport runs on {@link #io}.
 */
final class DeviceLink {
  private final Notices notices;
  ScheduledExecutorService timer;
  private final Runnable onFailure;
  final DeviceSession session = new SoxDeviceSession();
  /** Last project read from the device; null while working offline. */
  Project baseline;

  final ExecutorService io = newExecutor();
  final AtomicBoolean busy = new AtomicBoolean();
  Consumer<Boolean> onSettled = success -> {};

  DeviceLink(Notices notices, Runnable onFailure) {
    this.notices = notices;
    this.onFailure = onFailure;
  }

  static ExecutorService newExecutor() {
    return Executors.newSingleThreadExecutor(
        r -> {
          Thread t = new Thread(r, "sedona-device");
          t.setDaemon(true);
          return t;
        });
  }

  /**
   * Run one device job off the JavaFX thread; {@code done} is called back on it with the result.
   */
  <T> void work(String text, Callable<T> job, Consumer<T> done) {
    if (!busy.compareAndSet(false, true)) {
      notices.message(Tr.of("notice.busy"));
      return;
    }
    notices.message(text);
    io.submit(
        () -> {
          T result = null;
          Exception failure = null;
          try {
            result = job.call();
          } catch (Exception e) {
            failure = e;
          }
          final T value = result;
          final Exception error = failure;
          Platform.runLater(
              () -> {
                busy.set(false);
                boolean success = false;
                try {
                  if (error != null) {
                    onFailure.run();
                    notices.error(error);
                  } else {
                    done.accept(value);
                    success = true;
                  }
                } catch (Exception e) {
                  notices.error(e);
                } finally {
                  onSettled.accept(success);
                }
              });
        });
  }
}

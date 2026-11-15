package ru.bccontrol.sedonastudio.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import ru.bccontrol.sedonastudio.model.History;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.Tr;
import ru.bccontrol.sedonastudio.storage.AppFiles;
import ru.bccontrol.sedonastudio.toolchain.Sdk;
import ru.bccontrol.sedonastudio.toolchain.Toolchain;

/**
 * Replaces the whole application on the connected device - and its kit code when the draft uses
 * other kits - instead of applying changes one by one. The device restarts. Always explicit: a
 * prepared upload is shown, the password is asked again and a box must be ticked. What the device
 * had is kept in the operation folder and can be uploaded back with "restore".
 */
final class DeviceUpload {
  /** What is ready to go to the device. code is null when the device keeps its kit code. */
  record Prepared(byte[] app, byte[] code, String summary) {}

  private final History history;
  private final DeviceLink deviceLink;
  private final Notices notices;
  private final DeviceConnection connection;
  private final EditorShell shell;
  /**
   * Test hook: answers the confirmation instead of the dialog (the password, or null to cancel).
   */
  java.util.function.Function<Prepared, char[]> confirm = this::ask;
  /**
   * The password when the editor itself knows it (the simulator's one-run user); set by StudioApp.
   */
  java.util.function.Supplier<char[]> knownPassword = () -> null;

  DeviceUpload(
      History history,
      DeviceLink deviceLink,
      Notices notices,
      DeviceConnection connection,
      EditorShell shell) {
    this.history = history;
    this.deviceLink = deviceLink;
    this.notices = notices;
    this.connection = connection;
    this.shell = shell;
  }

  private void requireDevice() {
    if (!deviceLink.session.connected() || deviceLink.baseline == null)
      throw new IllegalStateException(Tr.of("apply.readFirst"));
  }

  /** Uploads the draft as the device's application. */
  void upload() {
    requireDevice();
    Project draft = history.get().copy();
    Project device = deviceLink.baseline.copy();
    String sdkPath = shell.prefs().get("sdkPath", null);
    Path work = Paths.get(System.getProperty("studio.data"), "upload");
    deviceLink.work(
        Tr.of("upload.preparing"),
        () -> {
          byte[] app = AppFiles.forDevice(draft, device, deviceLink.session.fetch("app.sab"));
          byte[] code = null;
          if (!new HashSet<>(draft.parts).equals(new HashSet<>(device.parts))) {
            // Other kits: the code must be rebuilt for exactly this device's platform.
            Sdk sdk =
                Sdk.find(sdkPath).orElseThrow(() -> new IllegalStateException(Tr.of("sim.noSdk")));
            if (sdk.platformId == null || !sdk.platformId.equals(device.platform))
              throw new IllegalStateException(
                  Tr.of("upload.platformDiffers", device.platform, String.valueOf(sdk.platformId)));
            code = Files.readAllBytes(new Toolchain(sdk).buildCode(draft.parts, work));
          }
          return new Prepared(
              app,
              code,
              code == null
                  ? Tr.of("upload.summaryApp", app.length)
                  : Tr.of("upload.summaryAppCode", app.length, code.length));
        },
        this::send);
  }

  /** Uploads back what an earlier operation saved from the device. */
  void restore() {
    requireDevice();
    DirectoryChooser chooser = new DirectoryChooser();
    chooser.setTitle(Tr.of("upload.chooseBackup"));
    File operations = Paths.get(System.getProperty("studio.data"), "operations").toFile();
    if (operations.isDirectory()) chooser.setInitialDirectory(operations);
    File chosen = chooser.showDialog(shell.stage());
    if (chosen == null) return;
    restoreFrom(chosen.toPath());
  }

  void restoreFrom(Path operation) {
    requireDevice();
    Path app = operation.resolve("saved-app-before.sab"),
        code = operation.resolve("kits-before.scode");
    if (!Files.isRegularFile(app))
      throw new IllegalArgumentException(Tr.of("upload.noBackup", operation));
    deviceLink.work(
        Tr.of("upload.preparing"),
        () -> {
          byte[] savedApp = Files.readAllBytes(app);
          byte[] savedCode = Files.isRegularFile(code) ? Files.readAllBytes(code) : null;
          // The code goes back only if the device runs another one now.
          if (savedCode != null && Arrays.equals(savedCode, deviceLink.session.fetch("kits.scode")))
            savedCode = null;
          return new Prepared(
              savedApp,
              savedCode,
              Tr.of("upload.summaryRestore", operation.getFileName())
                  + "\n"
                  + (savedCode == null
                      ? Tr.of("upload.summaryApp", savedApp.length)
                      : Tr.of("upload.summaryAppCode", savedApp.length, savedCode.length)));
        },
        this::send);
  }

  private void send(Prepared prepared) {
    char[] password = confirm.apply(prepared);
    if (password == null) return;
    Path journal =
        Paths.get(
            System.getProperty("studio.data"),
            "operations",
            java.time.LocalDateTime.now().toString().replace(':', '-') + "-upload");
    deviceLink.work(
        Tr.of("upload.sending", journal),
        () -> {
          Project after =
              deviceLink.session.deploy(prepared.app(), prepared.code(), journal, password);
          return new Object[] {after, deviceLink.session.catalog()};
        },
        result -> {
          connection.adopt(result);
          notices.message(Tr.of("upload.done", journal));
        });
  }

  /** The confirmation dialog: what goes, what it means, the password to log in again. */
  private char[] ask(Prepared prepared) {
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.initOwner(shell.stage());
    dialog.setTitle(Tr.of("upload.title"));
    Label summary = new Label(prepared.summary());
    Label warning = new Label(Tr.of("upload.warning"));
    warning.setWrapText(true);
    warning.setMaxWidth(560);
    PasswordField password = new PasswordField();
    password.setPromptText(Tr.of("upload.password"));
    password.setId("upload-password");
    CheckBox understood = new CheckBox(Tr.of("upload.understood"));
    char[] known = knownPassword.get();
    if (known != null) {
      // Never shown to anyone: fill it in and hide the field.
      password.setText(new String(known));
      Arrays.fill(known, '\0');
      password.setVisible(false);
      password.setManaged(false);
    }
    VBox content = new VBox(12, summary, warning, password, understood);
    dialog.getDialogPane().setContent(content);
    ButtonType send = new ButtonType(Tr.of("upload.send"), ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(send, ButtonType.CANCEL);
    dialog
        .getDialogPane()
        .lookupButton(send)
        .disableProperty()
        .bind(understood.selectedProperty().not().or(password.textProperty().isEmpty()));
    if (dialog.showAndWait().orElse(ButtonType.CANCEL) != send) return null;
    char[] secret = password.getText().toCharArray();
    password.clear();
    return secret;
  }
}

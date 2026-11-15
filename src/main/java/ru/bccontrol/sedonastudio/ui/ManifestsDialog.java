package ru.bccontrol.sedonastudio.ui;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javafx.application.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.shape.*;
import javafx.stage.*;
import ru.bccontrol.sedonastudio.model.*;
import ru.bccontrol.sedonastudio.protocol.*;
import ru.bccontrol.sedonastudio.storage.*;

/** Kit manifests: export from the device, import from files, local cache. */
final class ManifestsDialog {
  private final History history;
  private final DeviceLink deviceLink;
  private final Notices notices;
  private final ProjectFiles projectFiles;
  private final EditorShell shell;

  ManifestsDialog(
      History history,
      DeviceLink deviceLink,
      Notices notices,
      ProjectFiles projectFiles,
      EditorShell shell) {
    this.history = history;
    this.deviceLink = deviceLink;
    this.notices = notices;
    this.projectFiles = projectFiles;
    this.shell = shell;
  }

  void manifests() {
    TextArea list = new TextArea(String.join("\n", history.get().parts));
    list.setEditable(false);
    list.setPrefSize(540, 320);
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.initOwner(shell.stage());
    dialog.setTitle(Tr.of("manifests.title"));
    ButtonType export = new ButtonType(Tr.of("manifests.exportZip")),
        importFile = new ButtonType(Tr.of("manifests.importXmlZip"));
    ButtonType download = new ButtonType(Tr.of("manifests.download"));
    dialog.getDialogPane().setContent(new VBox(10, new Label(Tr.of("manifests.autoHint")), list));
    dialog.getDialogPane().getButtonTypes().addAll(download, export, importFile, ButtonType.CLOSE);
    ButtonType choice = dialog.showAndWait().orElse(ButtonType.CLOSE);
    if (choice == download) {
      if (!deviceLink.session.connected())
        throw new IllegalStateException(Tr.of("manifests.connectFirst"));
      deviceLink.work(
          Tr.of("manifests.downloading"),
          deviceLink.session::downloadManifests,
          count -> notices.message(Tr.of("manifests.downloaded", count)));
    }
    if (choice == export) {
      FileChooser chooser = new FileChooser();
      chooser.setInitialFileName("manifests.zip");
      File file = chooser.showSaveDialog(shell.stage());
      if (file == null) return;
      List<String> names = new ArrayList<>(history.get().parts);
      deviceLink.work(
          Tr.of("manifests.exporting"),
          () -> {
            sedona.KitPart[] parts = new sedona.KitPart[names.size()];
            for (int i = 0; i < parts.length; i++) parts[i] = sedona.KitPart.parse(names.get(i));
            byte[] archive = ManifestArchive.create(parts);
            Files.write(file.toPath(), archive);
            return parts.length;
          },
          count -> notices.message(Tr.of("manifests.exported", count)));
    }
    if (choice == importFile) {
      FileChooser chooser = new FileChooser();
      chooser
          .getExtensionFilters()
          .add(new FileChooser.ExtensionFilter(Tr.of("manifests.fileType"), "*.xml", "*.zip"));
      File file = chooser.showOpenDialog(shell.stage());
      if (file == null) return;
      deviceLink.work(
          Tr.of("manifests.importing"),
          () -> {
            if (Files.size(file.toPath()) > ManifestArchive.MAX_ARCHIVE)
              throw new IOException(Tr.of("device.archiveTooLarge"));
            return ManifestArchive.importManifests(Files.readAllBytes(file.toPath())).length;
          },
          count -> {
            notices.message(Tr.of("manifests.imported", count));
            if (!deviceLink.session.connected()) Platform.runLater(projectFiles::loadLocalPalette);
            else notices.message(Tr.of("manifests.deviceSchemaKept"));
          });
    }
  }
}

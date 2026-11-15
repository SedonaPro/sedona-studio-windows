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

/** The Edit and Options menus: only maps menu items to feature commands. */
final class EditorMenus {
  private final DeviceLink deviceLink;
  private final Notices notices;
  private final ViewOptions view;
  private final ProjectFiles projectFiles;
  private final EditActions editing;
  private final LiveValues liveValues;
  private final SettingsDialogs dialogs;
  private final DeviceConnection deviceConnection;
  private final DeviceChanges deviceChanges;
  private final ManifestsDialog manifestsDialog;
  private final DiagramView diagramView;
  private final KitWorkshopWindow kitWorkshop;
  private final DeviceUpload deviceUpload;
  private final EditorShell shell;

  EditorMenus(
      DeviceLink deviceLink,
      Notices notices,
      ViewOptions view,
      ProjectFiles projectFiles,
      EditActions editing,
      LiveValues liveValues,
      SettingsDialogs dialogs,
      DeviceConnection deviceConnection,
      DeviceChanges deviceChanges,
      ManifestsDialog manifestsDialog,
      DiagramView diagramView,
      KitWorkshopWindow kitWorkshop,
      DeviceUpload deviceUpload,
      EditorShell shell) {
    this.deviceLink = deviceLink;
    this.notices = notices;
    this.view = view;
    this.projectFiles = projectFiles;
    this.editing = editing;
    this.liveValues = liveValues;
    this.dialogs = dialogs;
    this.deviceConnection = deviceConnection;
    this.deviceChanges = deviceChanges;
    this.manifestsDialog = manifestsDialog;
    this.diagramView = diagramView;
    this.kitWorkshop = kitWorkshop;
    this.deviceUpload = deviceUpload;
    this.shell = shell;
  }

  private MenuItem option(String name, Runnable action) {
    MenuItem item = new MenuItem(name);
    item.setOnAction(
        e -> {
          if (deviceLink.busy.get()) {
            notices.message(Tr.of("notice.busy"));
            return;
          }
          notices.guard(action);
        });
    return item;
  }

  MenuButton optionsMenu() {
    MenuButton menu = new MenuButton(Tr.of("menu.options"));
    menu.setId("studio-options");
    Menu files = new Menu(Tr.of("common.project"));
    files
        .getItems()
        .addAll(
            option(Tr.of("menu.newProject"), projectFiles::newProject),
            option(Tr.of("menu.templates"), editing::templates),
            option(Tr.of("menu.exportApp"), projectFiles::exportApp),
            option(Tr.of("menu.kitWorkshop"), kitWorkshop::show),
            option(Tr.of("menu.exportCsv"), liveValues::exportCsv));
    Menu device = new Menu(Tr.of("menu.device"));
    device
        .getItems()
        .addAll(
            option(Tr.of("menu.manifests"), manifestsDialog::manifests),
            option(Tr.of("menu.compare"), deviceChanges::previewChanges),
            option(Tr.of("menu.recovery"), deviceChanges::recovery),
            new SeparatorMenuItem(),
            option(Tr.of("menu.upload"), deviceUpload::upload),
            option(Tr.of("menu.uploadRestore"), deviceUpload::restore),
            new SeparatorMenuItem(),
            option(Tr.of("menu.disconnect"), deviceConnection::disconnect));
    Menu view = new Menu(Tr.of("menu.canvas"));
    view.getItems()
        .addAll(
            option(Tr.of("menu.zoom100"), () -> diagramView.zoomControl.setValue(1)),
            option(Tr.of("menu.fit"), diagramView::fitDiagram),
            option(Tr.of("menu.pinLayout"), diagramView::commitLayout));
    menu.getItems()
        .addAll(
            option(Tr.of("menu.settings"), dialogs::settings),
            new SeparatorMenuItem(),
            files,
            device,
            view,
            new SeparatorMenuItem(),
            option(Tr.of("menu.help"), dialogs::help));
    return menu;
  }

  MenuButton editorMenu() {
    MenuButton menu = new MenuButton(Tr.of("menu.edit"));
    String[] names = {
      Tr.of("menu.copy"),
      Tr.of("menu.paste"),
      Tr.of("menu.duplicate"),
      Tr.of("menu.remove"),
      Tr.of("menu.alignLeft"),
      Tr.of("menu.alignTop"),
      Tr.of("menu.distributeH"),
      Tr.of("menu.distributeV"),
      Tr.of("menu.snapToGrid"),
      Tr.of("inspector.moveToFolder"),
      Tr.of("menu.highlightSignal")
    };
    Runnable[] actions = {
      editing::copySelection,
      editing::pasteSelection,
      editing::duplicate,
      editing::remove,
      () -> editing.arrangeSelection(0),
      () -> editing.arrangeSelection(1),
      () -> editing.arrangeSelection(2),
      () -> editing.arrangeSelection(3),
      editing::snapSelectionToGrid,
      editing::moveToFolder,
      () -> {
        view.highlightSignal = !view.highlightSignal;
        shell.world().selectionStyles();
      }
    };
    for (int i = 0; i < names.length; i++) {
      MenuItem item = new MenuItem(names[i]);
      Runnable action = actions[i];
      item.setOnAction(e -> notices.guard(action));
      menu.getItems().add(item);
    }
    return menu;
  }
}

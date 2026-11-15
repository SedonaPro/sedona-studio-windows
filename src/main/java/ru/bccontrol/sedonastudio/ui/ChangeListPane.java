package ru.bccontrol.sedonastudio.ui;

import ru.bccontrol.sedonastudio.model.Tr;
import java.util.*;
import javafx.collections.*;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import ru.bccontrol.sedonastudio.model.ProjectDiff;

/** Search changes without removing hidden operations from the eventual Apply plan. */
public final class ChangeListPane extends BorderPane {
  private final TextField search = new TextField();
  private final ComboBox<String> kind = new ComboBox<>();
  private final ListView<ProjectDiff.Change> list = new ListView<>();
  private final Label count = new Label();
  private final FilteredList<ProjectDiff.Change> filtered;

  public ChangeListPane(List<ProjectDiff.Change> changes) {
    filtered = new FilteredList<>(FXCollections.observableArrayList(changes));
    list.setItems(filtered);
    list.setPlaceholder(new Label(Tr.of("changes.noneForFilter")));
    search.setPromptText(Tr.of("changes.searchPrompt"));
    search.setId("change-search");
    kind.getItems().addAll(Tr.of("changes.all"), Tr.of("diff.addition"), Tr.of("diff.change"), Tr.of("diff.removal"));
    kind.setValue(Tr.of("changes.all"));
    kind.setId("change-kind");
    Button reset = new Button(Tr.of("changes.reset"));
    reset.setOnAction(
        e -> {
          search.clear();
          kind.setValue(Tr.of("changes.all"));
        });
    HBox controls = new HBox(8, search, kind, reset);
    HBox.setHgrow(search, Priority.ALWAYS);
    VBox top =
        new VBox(
            6,
            controls,
            count,
            new Label(
                Tr.of("changes.filterHint")));
    top.setPadding(new Insets(8));
    setTop(top);
    setCenter(list);
    Runnable update =
        () -> {
          String text = search.getText().strip().toLowerCase(Locale.ROOT),
              selected = kind.getValue();
          filtered.setPredicate(
              c ->
                  (selected.equals(Tr.of("changes.all")) || selected.equals(c.kind()))
                      && (c.subject() + "\n" + c.before() + "\n" + c.after())
                          .toLowerCase(Locale.ROOT)
                          .contains(text));
          long added = changes.stream().filter(c -> c.kind().equals(Tr.of("diff.addition"))).count(),
              removed = changes.stream().filter(c -> c.kind().equals(Tr.of("diff.removal"))).count();
          count.setText(
              Tr.of("changes.counts", filtered.size(), changes.size(), added, (changes.size() - added - removed), removed));
        };
    search.textProperty().addListener((o, a, b) -> update.run());
    kind.valueProperty().addListener((o, a, b) -> update.run());
    update.run();
    list.setCellFactory(
        v ->
            new ListCell<>() {
              protected void updateItem(ProjectDiff.Change c, boolean empty) {
                super.updateItem(c, empty);
                setText(
                    empty || c == null
                        ? null
                        : Tr.of("changes.item", c.kind(), c.subject(), c.before(), c.after()));
                setStyle(
                    empty || c == null
                        ? ""
                        : "-fx-text-fill: "
                            + (c.kind().equals(Tr.of("diff.addition"))
                                ? "#23816b"
                                : c.kind().equals(Tr.of("diff.removal")) ? "#b44444" : "#2775a0")
                            + "; -fx-padding: 10;");
              }
            });
    setPrefSize(760, 400);
  }

  void smokeChecks() {
    search.setText("aLpHa");
    if (filtered.size() != 1) throw new AssertionError("Case-insensitive search");
    kind.setValue(Tr.of("diff.removal"));
    if (!filtered.isEmpty()) throw new AssertionError("Combined filter");
    search.clear();
    if (filtered.size() != 1) throw new AssertionError("Kind filter");
    kind.setValue(Tr.of("changes.all"));
    search.setText("old-value");
    if (filtered.size() != 1) throw new AssertionError("Old value search");
    search.clear();
    if (filtered.size() != 3) throw new AssertionError("Filter reset");
    System.out.println(
        "PASS change search: case-insensitive subjects/values, combined kind filter, counts, reset"
            + " and full plan retained");
  }
}

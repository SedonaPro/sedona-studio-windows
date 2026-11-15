package ru.bccontrol.sedonastudio.ui;

import java.util.*;
import ru.bccontrol.sedonastudio.model.*;

/** What the user is looking at and has picked: the open folder, the selected blocks and wire. */
final class EditorFocus {
  private final History history;
  String selected;
  String folder = "0";
  final LinkedHashSet<String> selection = new LinkedHashSet<>();
  Project.Wire selectedWire;

  EditorFocus(History history) {
    this.history = history;
  }

  /** The selected block ids that still exist in the draft, primary selection first. */
  Set<String> selectedIds() {
    if (selected == null) {
      selection.clear();
      return Set.of();
    }
    if (!selection.contains(selected)) {
      selection.clear();
      selection.add(selected);
    }
    selection.removeIf(id -> !history.get().nodes.containsKey(id));
    return new LinkedHashSet<>(selection);
  }
}

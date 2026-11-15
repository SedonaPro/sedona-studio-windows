package ru.bccontrol.sedonastudio.ui;

import java.util.*;
import ru.bccontrol.sedonastudio.model.Project;

/** Component types the palette can offer, with the kits each one needs. */
final class TypeCatalog {
  final List<Project.Node> types = new ArrayList<>();
  final Map<String, List<String>> dependencies = new HashMap<>();

  /** Replace the offered types, for example with the catalog of a connected device. */
  void replace(List<Project.Node> fresh) {
    types.clear();
    dependencies.clear();
    types.addAll(fresh);
  }
}

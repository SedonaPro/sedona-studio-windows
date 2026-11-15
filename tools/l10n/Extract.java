import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;

/**
 * One-off migration tool: finds user-visible string expressions that contain Cyrillic text and
 * turns each into a Tr.of(key, args...) call. A "+" chain becomes one pattern with {n} placeholders.
 *
 * <p>scan: Extract scan OUT.tsv FILES... -> unique patterns, one per line (id TAB pattern TAB where)
 * apply: Extract apply KEYS.tsv FILES... -> rewrites the files; KEYS.tsv is "pattern TAB key"
 */
public final class Extract {
  static boolean cyr(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c >= 0x0400 && c <= 0x04FF) return true;
    }
    return false;
  }

  static String esc(String s) {
    return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r");
  }

  static String unesc(String s) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        char n = s.charAt(++i);
        b.append(n == 'n' ? '\n' : n == 't' ? '\t' : n == 'r' ? '\r' : n);
      } else b.append(c);
    }
    return b.toString();
  }

  record Site(long start, long end, String pattern, List<ExpressionTree> args) {}

  static final class Finder extends TreeScanner<Void, Void> {
    final SourcePositions pos;
    final CompilationUnitTree unit;
    final List<Site> sites = new ArrayList<>();
    final List<String> skipped = new ArrayList<>();

    Finder(SourcePositions pos, CompilationUnitTree unit) {
      this.pos = pos;
      this.unit = unit;
    }

    static void flatten(ExpressionTree t, List<ExpressionTree> out) {
      if (t instanceof BinaryTree b && b.getKind() == Tree.Kind.PLUS) {
        flatten(b.getLeftOperand(), out);
        out.add(b.getRightOperand());
      } else out.add(t);
    }

    static boolean str(ExpressionTree t) {
      return t instanceof LiteralTree l && l.getValue() instanceof String;
    }

    @Override
    public Void visitCase(CaseTree node, Void p) {
      for (ExpressionTree e : node.getExpressions())
        if (str(e) && cyr((String) ((LiteralTree) e).getValue()))
          skipped.add("case label: " + ((LiteralTree) e).getValue());
      if (node.getStatements() != null) scan(node.getStatements(), p);
      if (node.getBody() != null) scan(node.getBody(), p);
      return null;
    }

    @Override
    public Void visitAnnotation(AnnotationTree node, Void p) {
      return null;
    }

    @Override
    public Void visitBinary(BinaryTree node, Void p) {
      if (node.getKind() != Tree.Kind.PLUS) return super.visitBinary(node, p);
      List<ExpressionTree> ops = new ArrayList<>();
      flatten(node, ops);
      boolean hit = false;
      int first = -1;
      for (int i = 0; i < ops.size(); i++)
        if (str(ops.get(i))) {
          if (first < 0) first = i;
          if (cyr((String) ((LiteralTree) ops.get(i)).getValue())) hit = true;
        }
      if (!hit) return super.visitBinary(node, p);
      StringBuilder pattern = new StringBuilder();
      List<ExpressionTree> args = new ArrayList<>();
      int from = 0;
      if (first > 1) {
        // Operands before the first string literal may add up numerically: keep them as one value.
        ExpressionTree prefix = node;
        for (int i = ops.size() - 1; i >= first; i--) prefix = ((BinaryTree) prefix).getLeftOperand();
        pattern.append("{0}");
        args.add(prefix);
        from = first;
      }
      for (int i = from; i < ops.size(); i++) {
        ExpressionTree o = ops.get(i);
        if (str(o)) pattern.append((String) ((LiteralTree) o).getValue());
        else {
          pattern.append('{').append(args.size()).append('}');
          args.add(o);
        }
      }
      sites.add(new Site(pos.getStartPosition(unit, node), pos.getEndPosition(unit, node), pattern.toString(), args));
      return null;
    }

    @Override
    public Void visitLiteral(LiteralTree node, Void p) {
      if (node.getValue() instanceof String s && cyr(s))
        sites.add(new Site(pos.getStartPosition(unit, node), pos.getEndPosition(unit, node), s, List.of()));
      return null;
    }
  }

  static final String CALL = System.getProperty("l10n.call", "Tr.of");
  /** With a third column in KEYS.tsv the English text is written into the call as the fallback. */
  static Map<String, String> english;
  static Map<String, String> keys;
  static Map<String, String> found = new LinkedHashMap<>();

  /** Source of [start,end) with the sites inside it rewritten. */
  static String render(String src, Tree tree, long start, long end, SourcePositions pos, CompilationUnitTree unit, String where) {
    Finder f = new Finder(pos, unit);
    f.scan(tree, null);
    for (String s : f.skipped) System.err.println("SKIPPED " + where + ": " + s);
    StringBuilder out = new StringBuilder();
    long at = start;
    for (Site s : f.sites) {
      out.append(src, (int) at, (int) s.start());
      found.putIfAbsent(s.pattern(), where);
      if (keys == null) out.append(src, (int) s.start(), (int) s.end());
      else {
        String key = keys.get(s.pattern());
        if (key == null) throw new IllegalStateException("No key for: " + s.pattern());
        out.append(CALL).append("(\"").append(key).append('"');
        if (english != null) out.append(", \"").append(esc(english.get(s.pattern())).replace("\"", "\\\"")).append('"');
        for (ExpressionTree a : s.args()) {
          long as = pos.getStartPosition(unit, a), ae = pos.getEndPosition(unit, a);
          out.append(", ").append(render(src, a, as, ae, pos, unit, where));
        }
        out.append(')');
      }
      if (keys == null)
        for (ExpressionTree a : s.args())
          render(src, a, pos.getStartPosition(unit, a), pos.getEndPosition(unit, a), pos, unit, where);
      at = s.end();
    }
    out.append(src, (int) at, (int) end);
    return out.toString();
  }

  public static void main(String[] a) throws Exception {
    boolean apply = a[0].equals("apply");
    if (apply) {
      keys = new HashMap<>();
      for (String line : Files.readAllLines(Paths.get(a[1]), StandardCharsets.UTF_8)) {
        if (line.isBlank()) continue;
        String[] c = line.split("\t");
        keys.put(unesc(c[0]), c[1]);
        if (c.length > 2) {
          if (english == null) english = new HashMap<>();
          english.put(unesc(c[0]), unesc(c[2]));
        }
      }
    }
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    StandardJavaFileManager fm = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8);
    List<Path> files = new ArrayList<>();
    for (int i = 2; i < a.length; i++) files.add(Paths.get(a[i]));
    JavacTask task = (JavacTask) javac.getTask(null, fm, null, List.of("-proc:none"), null, fm.getJavaFileObjectsFromPaths(files));
    SourcePositions pos = Trees.instance(task).getSourcePositions();
    for (CompilationUnitTree unit : task.parse()) {
      Path file = Paths.get(unit.getSourceFile().toUri());
      String src = unit.getSourceFile().getCharContent(true).toString();
      String where = file.getFileName().toString().replace(".java", "");
      String out = render(src, unit, 0, src.length(), pos, unit, where);
      if (apply && !out.equals(src)) Files.write(file, out.getBytes(StandardCharsets.UTF_8));
    }
    if (!apply) {
      List<String> lines = new ArrayList<>();
      int n = 0;
      for (var e : found.entrySet()) lines.add(++n + "\t" + esc(e.getKey()) + "\t" + e.getValue());
      Files.write(Paths.get(a[1]), lines, StandardCharsets.UTF_8);
      System.out.println(n + " patterns");
    }
  }
}

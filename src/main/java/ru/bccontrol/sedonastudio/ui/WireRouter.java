package ru.bccontrol.sedonastudio.ui;

import java.util.*;
import javafx.geometry.*;

/**
 * Grid obstacle routing adapted from MCS LinkSnakeGlyph's component board/wavefront approach. Uses
 * per-instance state, a bounded A* search and bend/lane penalties instead of static ant arrays. No
 * Baja, Wixel or MCS runtime dependency.
 */
public final class WireRouter {
  private static final double STEP = 12;
  private final int cols, rows;
  private final boolean[] blocked;
  private final int[] traffic;
  private int remainingSearch = 250000;
  private long simpleRoutes, searches, expanded;

  public record Statistics(long simpleRoutes, long searches, long expanded) {}

  public Statistics statistics() {
    return new Statistics(simpleRoutes, searches, expanded);
  }
  /** Try only empty forward corridors. Occupied lanes retain A* traffic-aware routing. */
  private LinkedList<Point2D> simplePath(int sx, int sy, int tx, int ty) {
    if (tx < sx || traffic[sy * cols + sx] > 0 || traffic[ty * cols + tx] > 0) return null;
    int mid = (sx + tx) / 2;
    Set<Integer> lanes = new LinkedHashSet<>(List.of(mid, mid + 2, mid - 2, sx, tx));
    for (int lane : lanes) {
      if (lane < sx || lane > tx) continue;
      LinkedHashSet<Integer> cells = new LinkedHashSet<>();
      for (int x = sx; x <= lane; x++) cells.add(sy * cols + x);
      for (int y = Math.min(sy, ty); y <= Math.max(sy, ty); y++) cells.add(y * cols + lane);
      for (int x = lane; x <= tx; x++) cells.add(ty * cols + x);
      boolean clear = true;
      for (int cell : cells)
        if (blocked[cell] || traffic[cell] > 0) {
          clear = false;
          break;
        }
      if (!clear) continue;
      for (int cell : cells) traffic[cell]++;
      simpleRoutes++;
      return new LinkedList<>(
          List.of(
              new Point2D(sx * STEP, sy * STEP),
              new Point2D(lane * STEP, sy * STEP),
              new Point2D(lane * STEP, ty * STEP),
              new Point2D(tx * STEP, ty * STEP)));
    }
    return null;
  }

  private record Visit(int cell, int direction, double cost, double priority) {}

  public WireRouter(Collection<Bounds> obstacles, double width, double height) {
    cols = (int) Math.ceil(Math.min(12000, width) / STEP) + 1;
    rows = (int) Math.ceil(Math.min(12000, height) / STEP) + 1;
    blocked = new boolean[cols * rows];
    traffic = new int[blocked.length];
    for (Bounds b : obstacles)
      for (int y = Math.max(0, (int) Math.floor((b.getMinY() - 6) / STEP));
          y <= Math.min(rows - 1, (int) Math.ceil((b.getMaxY() + 6) / STEP));
          y++)
        for (int x = Math.max(0, (int) Math.floor((b.getMinX() - 6) / STEP));
            x <= Math.min(cols - 1, (int) Math.ceil((b.getMaxX() + 6) / STEP));
            x++) blocked[y * cols + x] = true;
  }

  public List<Point2D> route(Point2D source, Point2D target, Bounds sourceBox, Bounds targetBox) {
    int sx = (int) Math.ceil((sourceBox.getMaxX() + 24) / STEP),
        sy = (int) Math.round(source.getY() / STEP);
    int tx = (int) Math.floor((targetBox.getMinX() - 24) / STEP),
        ty = (int) Math.round(target.getY() / STEP);
    if (sx < 0
        || tx < 0
        || sx >= cols
        || tx >= cols
        || sy < 0
        || ty < 0
        || sy >= rows
        || ty >= rows) return List.of();
    int start = sy * cols + sx, end = ty * cols + tx;
    if (blocked[start] || blocked[end]) return List.of();
    LinkedList<Point2D> middle = simplePath(sx, sy, tx, ty);
    if (middle == null) {
      searches++;
      Map<Integer, Double> distance = new HashMap<>();
      Map<Integer, Integer> previous = new HashMap<>();
      PriorityQueue<Visit> queue =
          new PriorityQueue<>(
              Comparator.comparingDouble(Visit::priority)
                  .thenComparingDouble(Visit::cost)
                  .thenComparingInt(Visit::cell)
                  .thenComparingInt(Visit::direction));
      int startState = start * 4;
      distance.put(startState, 0.0);
      queue.add(new Visit(start, 0, 0, Math.abs(sx - tx) + Math.abs(sy - ty)));
      int goal = -1, expansions = 0;
      int[] dx = {1, 0, -1, 0}, dy = {0, 1, 0, -1};
      while (!queue.isEmpty() && expansions++ < 30000 && remainingSearch-- > 0) {
        expanded++;
        Visit v = queue.poll();
        int state = v.cell * 4 + v.direction;
        if (v.cost > distance.getOrDefault(state, Double.POSITIVE_INFINITY)) continue;
        if (v.cell == end) {
          goal = state;
          break;
        }
        int x = v.cell % cols, y = v.cell / cols;
        for (int d = 0; d < 4; d++) {
          int nx = x + dx[d], ny = y + dy[d];
          if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) continue;
          int next = ny * cols + nx;
          if (blocked[next]) continue;
          double cost = v.cost + 1 + (d == v.direction ? 0 : 3) + Math.min(traffic[next], 4) * 1.5;
          int ns = next * 4 + d;
          if (cost >= distance.getOrDefault(ns, Double.POSITIVE_INFINITY)) continue;
          distance.put(ns, cost);
          previous.put(ns, state);
          queue.add(new Visit(next, d, cost, cost + Math.abs(nx - tx) + Math.abs(ny - ty)));
        }
      }
      if (goal < 0) return List.of();
      middle = new LinkedList<>();
      for (int state = goal; ; state = previous.get(state)) {
        int cell = state / 4;
        traffic[cell]++;
        middle.addFirst(new Point2D((cell % cols) * STEP, (cell / cols) * STEP));
        if (state == startState) break;
      }
    }
    // Keep the exact port centre along the entire first/last horizontal run.
    // Grid rounding must never create a short vertical jog at the port escape.
    if (sy == ty && middle.stream().allMatch(p -> p.getY() == sy * STEP)) {
      middle.clear();
      double mid = (sx + tx) * STEP / 2;
      middle.add(new Point2D(sx * STEP, source.getY()));
      middle.add(new Point2D(mid, source.getY()));
      middle.add(new Point2D(mid, target.getY()));
      middle.add(new Point2D(tx * STEP, target.getY()));
    } else {
      double firstY = middle.getFirst().getY(), lastY = middle.getLast().getY();
      for (int i = 0; i < middle.size() && middle.get(i).getY() == firstY; i++) {
        Point2D p = middle.get(i);
        middle.set(i, new Point2D(p.getX(), source.getY()));
      }
      for (int i = middle.size() - 1; i >= 0 && middle.get(i).getY() == lastY; i--) {
        Point2D p = middle.get(i);
        middle.set(i, new Point2D(p.getX(), target.getY()));
      }
    }
    List<Point2D> result = new ArrayList<>();
    result.add(source);
    result.add(new Point2D(sx * STEP, source.getY()));
    result.addAll(middle);
    result.add(new Point2D(tx * STEP, target.getY()));
    result.add(target);
    for (int i = 1; i < result.size() - 1; ) {
      Point2D a = result.get(i - 1), b = result.get(i), c = result.get(i + 1);
      if (a.equals(b)
          || b.equals(c)
          || (a.getX() == b.getX() && b.getX() == c.getX())
          || (a.getY() == b.getY() && b.getY() == c.getY())) result.remove(i);
      else i++;
    }
    return result;
  }
}

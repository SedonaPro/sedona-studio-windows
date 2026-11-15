package ru.bccontrol.panel.widgets;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import ru.bccontrol.panel.api.PanelData.Quality;

/** Shared renderer: selection thumbnails and live faces use exactly the same drawing. */
final class WidgetFace extends Canvas {
  WidgetFace(double width, double height) { super(width, height); }
  void draw(String kind, String text, double value, double min, double max, String unit, Quality quality) {
    GraphicsContext g = getGraphicsContext2D();
    double w = getWidth(), h = getHeight();
    g.clearRect(0, 0, w, h);
    g.setFill(Color.web("#f4f8fc")); g.fillRoundRect(0, 0, w, h, 16, 16);
    boolean valid = Double.isFinite(value);
    Color accent = quality == Quality.GOOD ? Color.web("#159d95") : Color.web("#a57b44");
    double fraction = valid ? Math.max(0, Math.min(1, (value - min) / (max - min))) : 0;
    g.setTextAlign(TextAlignment.CENTER);
    g.setFont(Font.font("Segoe UI", FontWeight.BOLD, h * .22));
    String display = text.length() > 18 ? text.substring(0, 15) + "…" : text;
    if (java.util.Set.of("tank", "damper", "fan", "pump", "bipolar").contains(kind)) {
      scenario(g, kind, display, fraction, value, min, max, accent, valid, w, h);
    } else if (kind.equals("indicator")) {
      g.setFill(Color.web("#dce7ed")); g.fillOval(w/2-h*.25, h*.12, h*.5, h*.5);
      g.setFill(valid && value != 0 ? accent : Color.web("#95a7b5"));
      g.fillOval(w/2-h*.20, h*.17, h*.4, h*.4);
      g.setFill(Color.web("#203d55")); g.fillText(display, w/2, h*.85, w-16);
    } else if (kind.equals("gauge")) {
      double d = Math.min(w-30, h*1.1), x=(w-d)/2, y=h*.1;
      g.setLineWidth(Math.max(4,h*.07)); g.setStroke(Color.web("#dce7ed"));
      g.strokeArc(x,y,d,d,0,180,ArcType.OPEN);
      g.setStroke(accent); g.strokeArc(x,y,d,d,180,-180*fraction,ArcType.OPEN);
      double a=Math.PI*(1-fraction), cx=w/2, cy=y+d/2;
      if(valid) { g.setLineWidth(2);g.strokeLine(cx,cy,cx+Math.cos(a)*d*.35,cy-Math.sin(a)*d*.35); }
      g.setFill(Color.web("#203d55"));g.fillText(display,w/2,h*.83,w-18);
    } else if (kind.equals("thermometer")) {
      double x=w*.18; g.setFill(Color.web("#dce7ed"));g.fillRoundRect(x,10,14,h-30,14,14);
      g.setFill(accent);g.fillRoundRect(x+3,12+(h-36)*(1-fraction),8,(h-36)*fraction+4,8,8);
      g.fillOval(x-3,h-24,20,20);g.setFill(Color.web("#203d55"));g.fillText(display,w*.64,h*.55,w*.58);
    } else {
      g.setFill(Color.web("#203d55"));g.fillText(display,w/2,h*.48,w-18);
      if(kind.equals("bar")) {
        g.setFill(Color.web("#dce7ed"));g.fillRoundRect(12,h*.65,w-24,10,10,10);
        g.setFill(accent);g.fillRoundRect(12,h*.65,(w-24)*fraction,10,10,10);
      }
    }
    g.setFont(Font.font("Segoe UI", h*.11));g.setFill(Color.web("#688096"));
    if (!kind.equals("indicator"))g.fillText(unit,w/2,h*.98,w-12);
    if (!valid && !kind.equals("value")) { g.setFill(Color.web("#a57b44"));g.fillText("—",w-12,14); }
  }

  private void scenario(GraphicsContext g, String kind, String text, double f, double value,
      double min, double max, Color accent, boolean valid, double w, double h) {
    g.save(); g.scale(w/220, h/110);
    Color muted=Color.web("#dce7ed"), ink=Color.web("#203d55");
    g.setLineWidth(3);g.setStroke(ink);
    switch(kind) {
      case "tank" -> {
        g.setFill(muted);g.fillRoundRect(18,12,66,78,12,12);
        if(valid) {g.setFill(accent);g.fillRect(22,16+70*(1-f),58,70*f);}
        g.strokeRoundRect(18,12,66,78,12,12);
        for(int i=1;i<4;i++){g.setStroke(Color.WHITE);g.strokeLine(24,16+i*17.5,34,16+i*17.5);}
      }
      case "damper" -> {
        g.setFill(muted);g.fillRoundRect(15,18,80,70,8,8);g.strokeRoundRect(15,18,80,70,8,8);
        g.setStroke(valid?accent:Color.GRAY);
        for(int i=0;i<3;i++) {
          double cy=30+i*23, a=(1-f)*Math.PI/2, dx=Math.cos(a)*28, dy=Math.sin(a)*9;
          g.strokeLine(55-dx,cy-dy,55+dx,cy+dy);
        }
      }
      case "fan" -> {
        g.setFill(muted);g.fillOval(16,15,78,78);g.strokeOval(16,15,78,78);
        g.save();g.translate(55,54);g.rotate(valid?f*120:0);
        g.setFill(valid && f>0?accent:Color.web("#95a7b5"));
        for(int i=0;i<4;i++){g.fillOval(-7,-32,17,28);g.rotate(90);}
        g.setFill(ink);g.fillOval(-6,-6,12,12);g.restore();
      }
      case "pump" -> {
        boolean running=valid && value!=0;
        g.setFill(muted);g.fillRect(7,45,100,16);
        g.setFill(running?accent:Color.web("#95a7b5"));g.fillOval(25,24,60,60);
        g.strokeOval(25,24,60,60);g.setFill(Color.WHITE);
        g.fillPolygon(new double[]{44,44,69},new double[]{37,71,54},3);
        g.setStroke(ink);g.strokeLine(30,90,80,90);g.strokeLine(40,82,36,90);g.strokeLine(70,82,74,90);
      }
      case "bipolar" -> {
        double zero=Math.max(0,Math.min(1,(0-min)/(max-min)));
        g.setFill(muted);g.fillRoundRect(14,60,192,15,8,8);
        if(valid){g.setFill(value<0?Color.web("#668bd5"):accent);
          g.fillRect(14+192*Math.min(zero,f),60,192*Math.abs(f-zero),15);}
        g.setStroke(ink);g.setLineWidth(1);g.strokeLine(14+192*zero,54,14+192*zero,81);
        g.setFill(ink);g.setFont(Font.font("Segoe UI",FontWeight.BOLD,24));g.fillText(text,110,40,195);
      }
    }
    if(!kind.equals("bipolar")) {
      g.setFill(ink);g.setFont(Font.font("Segoe UI",FontWeight.BOLD,19));g.fillText(text,155,58,108);
    }
    g.restore();
  }
}

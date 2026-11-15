package ru.bccontrol.panel.widgets;

import java.nio.file.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.geometry.Insets;
import javafx.stage.Stage;
import ru.bccontrol.panel.api.PanelData.Quality;

/** Visual proof of the same renderer used by the selection thumbnails and live tiles. */
public final class WidgetGalleryTest {
  public static void main(String[] args) throws Exception {
    CountDownLatch finished=new CountDownLatch(1);
    Throwable[] failure={null};
    Platform.startup(() -> {
      try {
        GridPane grid=new GridPane();grid.setHgap(14);grid.setVgap(12);grid.setPadding(new Insets(18));
        grid.setStyle("-fx-background-color:#e8f0f6;");
        String[] kinds={"value","indicator","bar","gauge","thermometer","tank","damper","fan","pump","bipolar"};
        for(int i=0;i<kinds.length;i++) {
          String kind=kinds[i];WidgetFace face=new WidgetFace(240,105);
          boolean binary=kind.equals("pump") || kind.equals("indicator");
          face.draw(kind,binary?"ON":kind.equals("bipolar")?"-28.5":"62.5",binary?1:kind.equals("bipolar")?-28.5:62.5,
              kind.equals("bipolar")?-100:0,100,binary?"":"%",Quality.GOOD);
          VBox card=new VBox(5,new Label(kind),face);grid.add(card,i%2,i/2);
          // Missing data and bad quality must render without retaining an old face or throwing.
          WidgetFace unavailable=new WidgetFace(88,46);
          unavailable.draw(kind,"—",Double.NaN,-100,100,"",Quality.ERROR);
        }
        Stage stage=new Stage();Scene scene=new Scene(grid);stage.setScene(scene);stage.show();grid.applyCss();grid.layout();
        Path path=Path.of(args[0]);Files.createDirectories(path.toAbsolutePath().getParent());
        javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(scene.snapshot(null),null),"png",path.toFile());
        stage.close();System.out.println("PASS gallery: ten widget renderers, thumbnail size, missing data and quality");
      } catch(Throwable error) {failure[0]=error;} finally {finished.countDown();}
    });
    if(!finished.await(30,TimeUnit.SECONDS))throw new AssertionError("Gallery timeout");
    Platform.exit();if(failure[0]!=null)throw new AssertionError(failure[0]);
  }
}

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import javax.swing.*;
import com.jme3.gde.textureeditor.ImageEditorComponent;
import com.jme3.gde.textureeditor.tools.CropTool;
import org.openide.windows.TopComponent;

/** Swing navigation regression; run with the SDK and NetBeans runtime classpath. */
public class TextureNavigationSmoke {
    static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    static void call(Object object, String name) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(object);
    }

    static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layout(nested);
            }
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void navigate(Object editor, Component surface, JScrollPane scroll, String fitMethod) throws Exception {
        layout(scroll);
        scroll.dispatchEvent(new MouseWheelEvent(scroll, MouseEvent.MOUSE_WHEEL, 0, 0,
                120, 100, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -12));
        layout(scroll.getParent());
        check(!(boolean) field(editor, "fitView"), "Wheel leaves Fit");
        scroll.getViewport().setViewPosition(new Point(100, 100));
        Point before = scroll.getViewport().getViewPosition();
        surface.dispatchEvent(new MouseEvent(surface, MouseEvent.MOUSE_PRESSED, 0,
                MouseEvent.BUTTON3_DOWN_MASK, 180, 180, 1, false, MouseEvent.BUTTON3));
        surface.dispatchEvent(new MouseEvent(surface, MouseEvent.MOUSE_DRAGGED, 0,
                MouseEvent.BUTTON3_DOWN_MASK, 140, 150, 0, false, MouseEvent.NOBUTTON));
        surface.dispatchEvent(new MouseEvent(surface, MouseEvent.MOUSE_RELEASED, 0,
                0, 140, 150, 1, false, MouseEvent.BUTTON3));
        check(scroll.getViewport().getViewPosition().x > before.x, "Right drag pans");
        if (fitMethod != null) {
            call(editor, fitMethod);
        } else {
            Field fit = editor.getClass().getDeclaredField("fitView");
            fit.setAccessible(true);
            fit.set(editor, true);
            call(editor, "renderPreview");
        }
        layout(scroll.getParent());
        check((boolean) field(editor, "fitView"), "Fit restores automatic sizing");
    }

    public static void main(String[] args) throws Exception {
        try {
            verifyImageArrivesAfterLayout();
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = null;
                try {
                    ImageEditorComponent editor = ImageEditorComponent.create();
                    editor.getComponent().setSize(800, 500);
                    layout(editor.getComponent());
                    editor.setEditedImage(new TopComponent(), new BufferedImage(2048, 1024, 2), null);
                    layout(editor.getComponent());
                    JScrollPane scroll = (JScrollPane) field(editor, "scroller");
                    check(editor.getScaleFactor() < 1, "Regular editor opens fitted");
                    check(editor.getScaleFactor() * 2048 <= scroll.getViewport().getWidth(), "Full image fits");
                    editor.setCurrentTool(CropTool.create());
                    navigate(editor, editor.getImageCanvas(), scroll, "fitImage");
                    Class<?> type = Class.forName("com.jme3.gde.texturetools.TextureToolsAction$Editor");
                    Constructor<?> constructor = type.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    frame = (JFrame) constructor.newInstance();
                    layout(frame.getRootPane());
                    frame.getRootPane().setSize(800, 500);
                    layout(frame.getRootPane());
                    Field decoded = type.getDeclaredField("decoded");
                    decoded.setAccessible(true);
                    decoded.set(frame, new BufferedImage(2048, 1024, 2));
                    call(frame, "renderPreview");
                    JLabel image = (JLabel) field(frame, "image");
                    scroll = (JScrollPane) field(frame, "scroller");
                    check(image.getIcon().getIconWidth() <= scroll.getViewport().getWidth(), "Compressed editor opens fitted");
                    navigate(frame, image, scroll, null);
                    System.out.println("TEXTURE_NAVIGATION_PASS");
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                } finally {
                    if (frame != null) {
                        frame.dispose();
                    }
                }
            });
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static void verifyImageArrivesAfterLayout() throws Exception {
        JFrame[] frame = new JFrame[1];
        ImageEditorComponent[] editor = new ImageEditorComponent[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                editor[0] = ImageEditorComponent.create();
                editor[0].setLoadingState();
                frame[0] = new JFrame();
                frame[0].setContentPane(editor[0].getComponent());
                frame[0].setSize(800, 500);
                frame[0].setLocation(-4000, -4000);
                frame[0].setVisible(true);
            });
            SwingUtilities.invokeAndWait(() -> {
                BufferedImage decoded = new BufferedImage(2048, 1024, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = decoded.createGraphics();
                graphics.setColor(Color.MAGENTA);
                graphics.fillRect(0, 0, decoded.getWidth(), decoded.getHeight());
                graphics.dispose();
                try {
                    var file = org.openide.filesystems.FileUtil.createMemoryFileSystem().getRoot().createData("preview.hdr");
                    editor[0].setEditedImage(new TopComponent(), decoded, file);
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });
            SwingUtilities.invokeAndWait(() -> {
                var canvas = editor[0].getImageCanvas();
                check(canvas.getWidth() > 100 && canvas.getHeight() > 100,
                        "Async image left a blank canvas after the viewport was already laid out");
                BufferedImage snapshot = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = snapshot.createGraphics();
                canvas.paint(graphics);
                graphics.dispose();
                check(snapshot.getRGB(snapshot.getWidth() / 2, snapshot.getHeight() / 2) == Color.MAGENTA.getRGB(),
                        "HDR preview did not paint the decoded image");
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (frame[0] != null) {
                    frame[0].dispose();
                }
            });
        }
    }
}

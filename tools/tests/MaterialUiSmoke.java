import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import javax.swing.*;
import com.jme3.gde.materials.multiview.MaterialEditorTopComponent;
import com.jme3.gde.materials.multiview.widgets.*;
import com.jme3.gde.materials.MaterialPreviewRenderer;
import com.jme3.gde.core.scene.PreviewRequest;
import com.jme3.math.Vector3f;

public class MaterialUiSmoke {
    public static class TestLookup extends org.openide.util.lookup.ProxyLookup {
        public TestLookup() {
            super(org.openide.util.lookup.Lookups.fixed(MaterialUiSmoke.class.getClassLoader(),
                    new org.openide.util.svg.SVGLoaderImpl()));
        }
    }
    static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }
    static void call(Object o, String name) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(o);
    }
    static void layout(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) {
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
    public static void main(String[] args) throws Exception {
        System.setProperty("org.openide.util.Lookup", TestLookup.class.getName());
        SwingUtilities.invokeAndWait(() -> {
            try {
                MaterialEditorTopComponent editor = new MaterialEditorTopComponent();
                call(editor, "initComponents");
                call(editor, "configureCompactLayout");
                JTabbedPane tabs = (JTabbedPane) field(editor, "optionsPane");
                check(tabs.getTabCount() == 3, "one tab strip with three pages");
                JPanel content = (JPanel) field(editor, "texturePanel");
                for (int i = 0; i < 40; i++) {
                    JLabel row = new JLabel("A long material parameter name " + i);
                    row.setPreferredSize(new Dimension(1000, 42));
                    row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
                    content.add(row);
                }
                tabs.setSelectedIndex(1);
                for (int width : new int[]{1100, 560}) {
                    editor.setSize(width, 620);
                    layout(editor);
                    JScrollPane scroll = (JScrollPane) tabs.getSelectedComponent();
                    check(scroll.getViewport().getView().getWidth() == scroll.getViewport().getExtentSize().width,
                            "content must track viewport width");
                    check(scroll.getVerticalScrollBar().getUnitIncrement(1) == 32, "wheel increment");
                    BufferedImage image = new BufferedImage(width, 620, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = image.createGraphics();
                    editor.paint(g);
                    g.dispose();
                    javax.imageio.ImageIO.write(image, "png", new java.io.File(args[0] + "/layout-" + width + ".png"));
                }
                MaterialPreviewWidget widget = (MaterialPreviewWidget) field(editor, "materialPreviewWidget1");
                check(((JToggleButton) field(widget, "togglePbrEnvButton")).isSelected(), "environment default on");
                check(((JToggleButton) field(widget, "cubeButton")).getIcon().getIconWidth() == 18, "small icon");
                MaterialPreviewRenderer renderer = new MaterialPreviewRenderer(new JLabel());
                renderer.dragPreview(30, 20, false);
                renderer.dragPreview(15, -10, true);
                check((float) field(renderer, "orbitYaw") != 0, "orbit changes");
                check((float) field(renderer, "lightYaw") != -0.5f, "light direction changes");
                PreviewRequest request = new PreviewRequest(renderer, new com.jme3.scene.Node());
                Vector3f direction = new Vector3f(1, -1, -1);
                request.setMaterialLighting(direction, true);
                direction.set(0, 0, 0);
                check(((Vector3f) field(request, "materialLightDirection")).length() > 0.99f, "copy direction");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        // Exercise request-local light isolation without starting the shared SDK application.
        com.jme3.asset.AssetManager assets = com.jme3.system.JmeSystem.newAssetManager(
                com.jme3.system.JmeSystem.getPlatformAssetConfigURL());
        com.jme3.scene.Spatial environment = assets.loadModel("com/jme3/gde/core/sceneviewer/pbrenv.j3o");
        com.jme3.light.LightProbe probe = (com.jme3.light.LightProbe) environment.getLocalLightList().get(0);
        check(probe.isReady() && probe.getPrefilteredEnvMap() != null, "bundled PBR environment is ready");
        com.jme3.gde.core.scene.ScenePreviewProcessor processor = new com.jme3.gde.core.scene.ScenePreviewProcessor();
        Field env = processor.getClass().getDeclaredField("materialEnvironment");
        env.setAccessible(true);
        env.set(processor, com.jme3.light.SkyLight.fromEnvironment(probe));
        com.jme3.scene.Node root = (com.jme3.scene.Node) field(processor, "previewNode");
        com.jme3.light.PointLight legacy = new com.jme3.light.PointLight();
        root.addLight(legacy);
        Method apply = processor.getClass().getDeclaredMethod("applyMaterialLighting", PreviewRequest.class);
        apply.setAccessible(true);
        Field current = processor.getClass().getDeclaredField("currentPreviewRequest");
        current.setAccessible(true);
        for (boolean enabled : new boolean[]{true, false}) {
            PreviewRequest request = new PreviewRequest(processor, new com.jme3.scene.Node());
            request.setMaterialLighting(new Vector3f(1, -1, -1), enabled);
            current.set(processor, request);
            apply.invoke(processor, request);
            check(root.getLocalLightList().size() == (enabled ? 2 : 1), "isolated lights and environment toggle");
            call(processor, "restorePreviewLighting");
            check(root.getLocalLightList().size() == 1 && root.getLocalLightList().get(0) == legacy,
                    "restore shared preview lights");
        }
        System.out.println("MATERIAL_UI_SMOKE_PASS");
        System.exit(0);
    }
}

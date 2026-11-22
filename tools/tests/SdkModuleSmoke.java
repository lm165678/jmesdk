package sdkverification;

import com.jme3.gde.core.assets.ProjectAssetManager;
import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.scene.SceneRequest;
import com.jme3.gde.core.sceneexplorer.nodes.*;
import com.jme3.gde.core.sceneviewer.SceneViewerTopComponent;
import com.jme3.gde.core.undoredo.SceneUndoRedoManager;
import com.jme3.scene.*;
import com.jme3.scene.shape.Box;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import java.awt.datatransfer.Transferable;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.openide.modules.ModuleInstall;
import org.openide.util.Lookup;
import org.openide.util.datatransfer.PasteType;

/** Runs module loading, clipboard and undo against a separate NetBeans test cluster. */
public final class SdkModuleSmoke extends ModuleInstall {
    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
    @Override
    public void restored() {
        Thread thread = new Thread(() -> {
            try {
                runVerification();
            } catch (Throwable error) {
                error.printStackTrace();
                System.err.println("SDK_MODULE_EDITOR_FAIL " + error);
            }
        }, "SDK module verification");
        thread.setDaemon(true);
        org.openide.windows.WindowManager.getDefault().invokeWhenUIReady(thread::start);
    }
    private void runVerification() throws Exception {
        SceneApplication app = SceneApplication.getApplication();
        app.enqueue(() -> {
            require(app.getContext().getClass().getName().endsWith("PreviewPanelsContext"), "Actual modular custom context");
            return null;
        }).get(60, TimeUnit.SECONDS);
        System.err.println("SDK_MODULE_CONTEXT_PASS");
        ProjectAssetManager assets = new ProjectAssetManager();
        Node root = new Node("Verification scene");
        Node model = new Node("Model");
        Geometry first = new Geometry("Box", new Box(1, 1, 1));
        Material material = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", ColorRGBA.Orange);
        first.setMaterial(material);
        model.attachChild(first);
        Geometry second = first.clone();
        second.setName("Other box");
        second.setLocalTranslation(3, 0, 0);
        model.attachChild(second);
        Node destination = new Node("Destination");
        root.attachChild(model);
        root.attachChild(destination);
        JmeNode tree = NodeUtility.createNode(root, false);
        SceneRequest request = new SceneRequest(app, tree, assets);
        request.setWindowTitle("SDK editor verification");
        app.openScene(request);
        SwingUtilities.invokeAndWait(() -> { });
        app.enqueue(() -> null).get(30, TimeUnit.SECONDS);
        SceneUndoRedoManager history = Lookup.getDefault().lookup(SceneUndoRedoManager.class);
        require(history != null && history == Lookup.getDefault().lookup(org.openide.awt.UndoRedo.class), "One shared undo manager for both lookup types");
        SwingUtilities.invokeAndWait(() -> {
            try {
                tree.getChildren().getNodes(true);
                JmeNode modelNode = (JmeNode) tree.getChild(model);
                JmeNode destinationNode = (JmeNode) tree.getChild(destination);
                history.discardAllEdits();
                Transferable cut = modelNode.clipboardCut();
                destinationNode.getDropType(cut, 3, -1);
                require(model.getParent() == root, "Clipboard inspection must not detach a cut node");
                Transferable copied = modelNode.clipboardCopy();
                PasteType paste = destinationNode.getDropType(copied, 3, -1);
                paste.paste();
                paste.paste();
                require(destination.getQuantity() == 2 && destination.getChild(0) != destination.getChild(1), "Each paste must create a fresh model");
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        SwingUtilities.invokeAndWait(() -> {
            history.undo();
            require(destination.getQuantity() == 1, "Undo second paste");
            history.redo();
            require(destination.getQuantity() == 2, "Redo second paste");
            history.discardAllEdits();
            try {
                JmeNode modelNode = (JmeNode) tree.getChild(model);
                modelNode.destroy();
                require(model.getParent() == null && model.getQuantity() == 2, "Delete must retain subtree for undo");
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        SwingUtilities.invokeAndWait(() -> {
            history.undo();
            require(model.getParent() == root && model.getQuantity() == 2, "Undo subtree deletion");
            history.redo();
            require(model.getParent() == null && model.getQuantity() == 2, "Redo subtree deletion");
            history.undo();
            history.discardAllEdits();
            try {
                JmeNode modelNode = (JmeNode) tree.getChild(model);
                JmeNode destinationNode = (JmeNode) tree.getChild(destination);
                Transferable cut = modelNode.clipboardCut();
                destinationNode.getDropType(cut, 3, -1).paste();
                require(model.getParent() == destination, "Cut commit");
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        SwingUtilities.invokeAndWait(() -> {
            history.undo();
            require(model.getParent() == root, "Undo cut");
            history.redo();
            require(model.getParent() == destination, "Redo cut");
            history.undo();
            SceneViewerTopComponent.findInstance().open();
            SceneViewerTopComponent.findInstance().requestVisible();
            com.jme3.gde.scenecomposer.ModelDetailTopComponent.showSelection(model, null);
            require(javax.swing.UIManager.getLookAndFeel().getClass().getName().contains("FlatDarkLaf"), "Dark FlatLaf active");
        });
        verifyViewport(app, tree, assets, request);
        System.err.println("SDK_MODULE_EDITOR_PASS");
    }
    private void verifyViewport(SceneApplication app, JmeNode tree, ProjectAssetManager assets,
            SceneRequest request) throws Exception {
        var inspector = com.jme3.gde.scenecomposer.SceneComposerTopComponent.findInstance();
        var controller = new com.jme3.gde.scenecomposer.SceneComposerToolController(new Node("HUD test tools"), assets, tree);
        controller.setEditorController(new com.jme3.gde.scenecomposer.SceneEditorController(tree, null));
        controller.createOnTopToolNode();
        var camera = new com.jme3.gde.scenecomposer.ComposerCameraController(app.getCamera(), tree);
        camera.setToolController(controller);
        controller.setCamController(camera);
        SwingUtilities.invokeAndWait(() -> {
            try {
                var field = inspector.getClass().getDeclaredField("currentRequest");
                field.setAccessible(true);
                field.set(inspector, request);
                field = inspector.getClass().getDeclaredField("toolController");
                field.setAccessible(true);
                field.set(inspector, controller);
                org.openide.util.NbPreferences.forModule(inspector.getClass()).remove("verticalInspectorDockV2");
                var migrate = inspector.getClass().getDeclaredMethod("migrateInspectorDock");
                migrate.setAccessible(true);
                migrate.invoke(inspector);
                inspector.open();
                inspector.requestVisible();
                require(inspector.getMinimumSize().width <= 200, "Inspector must allow narrow docking");
                require(org.openide.windows.WindowManager.getDefault().findMode(inspector).getName().equals("explorer"), "Inspector left dock");
                var scroll = (javax.swing.JScrollPane) inspector.getComponent(0);
                require(((javax.swing.Scrollable) scroll.getViewport().getView()).getScrollableTracksViewportWidth(), "Inspector tracks viewport width");
                if (app.getActiveCameraController() != null) {
                    app.getActiveCameraController().disable();
                }
                camera.enable();
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        app.enqueue(() -> {
            camera.update(0);
            boolean visible = app.getInputManager().isCursorVisible();
            camera.onAction("MouseButtonRight", true, 0);
            require(!app.getInputManager().isCursorVisible(), "Right mouse hides cursor");
            camera.onAction("MouseButtonRight", false, 0);
            require(app.getInputManager().isCursorVisible() == visible, "Release restores cursor");
            camera.onAction("MouseButtonRight", true, 0);
            camera.resetInputState();
            require(app.getInputManager().isCursorVisible() == visible, "Focus reset restores cursor");
            for (int key : new int[]{com.jme3.input.KeyInput.KEY_Q, com.jme3.input.KeyInput.KEY_W, com.jme3.input.KeyInput.KEY_E, com.jme3.input.KeyInput.KEY_R}) {
                camera.onKeyEvent(new com.jme3.input.event.KeyInputEvent(key, ' ', true, false));
                camera.onKeyEvent(new com.jme3.input.event.KeyInputEvent(key, ' ', false, false));
                camera.update(0);
                Node hud = (Node) app.getGuiNode().getChild("Editor transform HUD");
                int index = key == com.jme3.input.KeyInput.KEY_Q ? 0 : key == com.jme3.input.KeyInput.KEY_W ? 1 : key == com.jme3.input.KeyInput.KEY_E ? 2 : 3;
                Geometry button = (Geometry) hud.getChild(index * 3);
                ColorRGBA color = (ColorRGBA) button.getMaterial().getParam("Color").getValue();
                require(color.b > .7f, "Selected transform highlighted");
            }
            float scale = .75f * app.getCamera().getWidth() / app.getMainPanel().getWidth();
            int x = (int) (30 * scale);
            int y = (int) (app.getCamera().getHeight() - 35 * scale);
            camera.onMouseButtonEvent(new com.jme3.input.event.MouseButtonEvent(0, true, x, y));
            camera.onAction("MouseButtonLeft", true, 0);
            camera.onAction("MouseButtonLeft", false, 0);
            camera.update(0);
            Node hud = (Node) app.getGuiNode().getChild("Editor transform HUD");
            ColorRGBA color = (ColorRGBA) ((Geometry) hud.getChild(0)).getMaterial().getParam("Color").getValue();
            require(color.b > .7f, "HUD click selects Select without scene picking");
            return null;
        }).get(30, TimeUnit.SECONDS);
        for (int cap : new int[]{30, 120, -1, 60}) {
            app.setPreviewFrameLimit(cap);
            app.enqueue(() -> {
                require(app.getPreviewFrameLimit() == cap, "Cap state updated");
                require(app.getContext().getSettings().getFrameRate() == cap, "Wrapper cap updated");
                var field = com.jme3.system.awt.AwtPanelsContext.class.getDeclaredField("actualContext");
                field.setAccessible(true);
                com.jme3.system.JmeContext actual = (com.jme3.system.JmeContext) field.get(app.getContext());
                require(actual.getSettings().getFrameRate() == cap, "Actual renderer cap updated");
                return null;
            }).get(30, TimeUnit.SECONDS);
        }
        SwingUtilities.invokeAndWait(() -> {
            require(inspector.isOpened(), "Inspector remains open after docking");
            com.jme3.gde.core.util.EditorUiStyle.setTheme("Light");
            require(javax.swing.UIManager.getColor("Panel.background").getRed() > 150, "Light palette restored");
            com.jme3.gde.core.util.EditorUiStyle.setTheme("Dark");
            require(javax.swing.UIManager.getColor("Panel.background").getRed() < 60, "Dark palette restored");
        });
        javax.swing.JToggleButton[] stats = new javax.swing.JToggleButton[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                var viewer = SceneViewerTopComponent.findInstance();
                var field = viewer.getClass().getDeclaredField("enableStats");
                field.setAccessible(true);
                stats[0] = (javax.swing.JToggleButton) field.get(viewer);
                require(stats[0].getParent() instanceof javax.swing.JToolBar, "Stats button is in toolbar");
                require(stats[0].getText().equals("Stats"), "Default English label");
                stats[0].doClick();
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        app.enqueue(() -> {
            require(app.getGuiNode().getChild("Stats Gui Node") != null, "Stats visible");
            return null;
        }).get(30, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> stats[0].doClick());
        app.enqueue(() -> {
            require(app.getGuiNode().getChild("Stats Gui Node") == null, "Stats hidden");
            return null;
        }).get(30, TimeUnit.SECONDS);
        System.err.println("SDK_VIEWPORT_LAYOUT_CURSOR_HUD_FPS_THEME_STATS_PASS");
    }

}

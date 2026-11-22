package com.jme3.gde.scenecomposer.tools;

import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.scene.SceneRequest;
import com.jme3.gde.core.sceneexplorer.SceneExplorerTopComponent;
import com.jme3.gde.core.sceneexplorer.nodes.AbstractSceneExplorerNode;
import com.jme3.gde.core.sceneexplorer.nodes.JmeDirectionalLight;
import com.jme3.gde.core.sceneexplorer.nodes.JmeNode;
import com.jme3.gde.core.sceneexplorer.nodes.JmeSpatialChildren;
import com.jme3.gde.core.undoredo.SceneUndoRedoManager;
import com.jme3.gde.scenecomposer.SceneComposerToolController;
import com.jme3.gde.scenecomposer.SceneComposerTopComponent;
import com.jme3.gde.scenecomposer.SceneEditTool;
import com.jme3.gde.scenecomposer.tools.shortcuts.ShortcutManager;
import com.jme3.input.KeyInput;
import com.jme3.input.event.KeyInputEvent;
import com.jme3.light.DirectionalLight;
import com.jme3.light.Light;
import com.jme3.light.PointLight;
import com.jme3.light.SpotLight;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.netbeans.core.startup.preferences.PreferencesProviderImpl;
import org.openide.util.svg.SVGLoaderImpl;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;
import org.openide.util.lookup.ProxyLookup;

/** Angle and native AWT regressions for the editor's transform handles. */
public final class TransformGizmoSmoke {
    public static void main(String[] args) {
        System.setProperty("org.openide.util.Lookup", TestLookup.class.getName());
        try {
            verifyDrag();
            verifyAwt();
            System.out.println("TRANSFORM_GIZMO_PASS");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    public static final class TestLookup extends ProxyLookup {
        public TestLookup() {
            super(Lookups.fixed(TransformGizmoSmoke.class.getClassLoader(), new SVGLoaderImpl(),
                    new PreferencesProviderImpl(), new org.netbeans.modules.openide.util.NbMutexEventProvider(),
                    new PickManager(), new ShortcutManager(), new SceneUndoRedoManager()));
        }
    }

    private static void verifyDrag() {
        Camera camera = new Camera(800, 600);
        camera.setFrustumPerspective(45, 800f / 600f, .1f, 200);
        Vector2f press = new Vector2f(400, 300);
        Vector2f up = press.add(new Vector2f(0, 36));
        for (int side : new int[]{-1, 1}) {
            camera.setLocation(new Vector3f(side * 10, 0, 0));
            camera.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
            var drag = new AxisTranslationDrag(camera, Vector3f.ZERO, Vector3f.UNIT_X, press);
            check(drag.translation(press).equals(Vector3f.ZERO), "Press moved the object");
            Vector3f delta = drag.translation(up);
            check(delta.x * -side > .45f && delta.y == 0 && delta.z == 0, "End-on axis locked: " + delta);
            check(drag.translation(press.add(new Vector2f(0, -36))).add(delta).length() < .001f,
                    "Reversing drag did not return");
        }
        camera.setLocation(new Vector3f(10, 0, .2f));
        camera.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        var near = new AxisTranslationDrag(camera, Vector3f.ZERO, Vector3f.UNIT_X, press);
        check(near.translation(up).x < -.45f && near.translation(up).length() < .6f, "Near end-on gain");
        camera.setLocation(new Vector3f(0, 0, 10));
        camera.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        Vector3f diagonal = new Vector3f(1, 1, 0).normalizeLocal();
        var oblique = new AxisTranslationDrag(camera, Vector3f.ZERO, diagonal, press);
        var screen = camera.getScreenCoordinates(diagonal).subtractLocal(camera.getScreenCoordinates(Vector3f.ZERO));
        Vector3f delta = oblique.translation(press.add(new Vector2f(screen.x, screen.y)));
        check(delta.distance(diagonal) < .001f, "Drag did not follow the rotated local axis");
        camera.setParallelProjection(true);
        camera.setFrustum(.1f, 200f, -8f, 8f, 6f, -6f);
        var ortho = new AxisTranslationDrag(camera, Vector3f.ZERO, Vector3f.UNIT_Z, press);
        check(Math.abs(ortho.translation(up).z + .72f) < .001f, "Orthographic depth movement");
        System.out.println("AXIS_DRAG_PASS");
    }

    private static void verifyAwt() throws Exception {
        SceneApplication app = SceneApplication.getApplication();
        JFrame[] window = new JFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                window[0] = new JFrame("Transform gizmo smoke");
                window[0].setUndecorated(true);
                window[0].setFocusableWindowState(false);
                window[0].add(app.getMainPanel());
                window[0].setSize(800, 600);
                window[0].setLocation(-10000, -10000);
                window[0].setVisible(true);
            });
            var root = new Node("Test scene");
            app.enqueue(() -> {
                var toggle = SceneApplication.class.getDeclaredMethod("setEditorHelpersVisibleInThread", boolean.class);
                toggle.setAccessible(true);
                boolean toolsEnabled = app.getEditorToolsView().isEnabled();
                boolean overlayEnabled = app.getOverlayView().isEnabled();
                toggle.invoke(app, false);
                check(!app.getEditorToolsView().isEnabled() && !app.getOverlayView().isEnabled(),
                        "G left a helper viewport enabled");
                toggle.invoke(app, true);
                check(app.getEditorToolsView().isEnabled() == toolsEnabled
                        && app.getOverlayView().isEnabled() == overlayEnabled, "G did not restore viewports");
                var startType = Class.forName("com.jme3.gde.scenecomposer.gizmo.StartGizmo");
                var create = startType.getDeclaredMethod("create", com.jme3.asset.AssetManager.class, com.jme3.scene.Start.class);
                create.setAccessible(true);
                Node start = (Node) create.invoke(null, app.getAssetManager(), new com.jme3.scene.Start());
                check(start.getChild("Start camera") != null, "Missing camera icon");
                Node axes = (Node) start.getChild("Start axes");
                for (String name : new String[]{"Forward", "Up"}) {
                    Node arrow = (Node) axes.getChild(name);
                    check(arrow.getChild("shaft") instanceof Geometry && arrow.getChild("tip") instanceof Geometry,
                            "Start arrow is not solid: " + name);
                    var expected = name.equals("Forward") ? com.jme3.math.ColorRGBA.Cyan : com.jme3.math.ColorRGBA.Green;
                    check(expected.equals(((Geometry) arrow.getChild("shaft")).getMaterial().getParam("Color").getValue()),
                            "Start axis color changed");
                }
                System.out.println("EDITOR_HELPER_VISIBILITY_AND_START_PASS");
                return null;
            }).get(30, TimeUnit.SECONDS);
            if (Boolean.getBoolean("jme.test.helpersOnly")) {
                return;
            }
            var branch = new Node("Model");
            var pivot = new Geometry("Selected test pivot", new Box(1, 1, 1));
            var survivor = new Geometry("Another selected model", new Box(1, 1, 1));
            var explorer = new JmeNode(root, new JmeSpatialChildren(root));
            var controller = app.enqueue(() -> {
                check(app.getRenderer().getCaps().contains(Caps.OpenGL33), "No OpenGL 3.3 context");
                root.attachChild(branch);
                branch.attachChild(pivot);
                root.attachChild(survivor);
                root.updateGeometricState();
                var tools = new SceneComposerToolController(new Node("Test tools"), app.getAssetManager(), explorer) {
                    @Override
                    public void setNeedsSave(boolean needsSave) {
                        // This fixture has no scene file.
                    }
                };
                tools.doSetShowSelection(true);
                tools.doUpdateSelection(pivot);
                tools.createOnTopToolNode();
                return tools;
            }).get(30, TimeUnit.SECONDS);
            var toolField = SceneComposerToolController.class.getDeclaredField("editTool");
            toolField.setAccessible(true);
            var markerField = SceneEditTool.class.getDeclaredField("axisMarker");
            markerField.setAccessible(true);
            var pickType = SceneEditTool.class.getDeclaredField("axisPickType");
            pickType.setAccessible(true);
            var pick = SceneEditTool.class.getDeclaredMethod("pickAxisMarker", Camera.class, Vector2f.class,
                    pickType.getType());
            pick.setAccessible(true);
            app.enqueue(() -> {
                Camera camera = app.getOverlayView().getCamera();
                camera.setParallelProjection(false);
                camera.setFrustumPerspective(45f, (float) camera.getWidth() / camera.getHeight(), .1f, 350f);
                for (int key : new int[]{KeyInput.KEY_Q, KeyInput.KEY_W, KeyInput.KEY_E, KeyInput.KEY_R}) {
                    camera.setLocation(new Vector3f(0, 0, 15));
                    camera.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
                    controller.doKeyPressed(new KeyInputEvent(key, '\0', true, false));
                    var tool = (SceneEditTool) toolField.get(controller);
                    var marker = (Node) markerField.get(tool);
                    check(tool.getCamera() == camera, "Tool had no viewport camera before mouse event");
                    check(Math.abs(screenRadius(camera, marker, app.getMainPanel().getHeight()) - 80) < 1f,
                            "Initial screen size after switch " + key);
                    if (key == KeyInput.KEY_Q || key == KeyInput.KEY_W) {
                        check(marker.getChild("coneX") != null && marker.getChild("coneY") != null
                                && marker.getChild("coneZ") != null, "Missing axis arrowheads in mode " + key);
                    }
                    if (key != KeyInput.KEY_Q) {
                        marker.getParent().updateGeometricState();
                        var center = camera.getScreenCoordinates(marker.getWorldTranslation());
                        float pixels = (float) camera.getHeight() / app.getMainPanel().getHeight();
                        boolean rotate = key == KeyInput.KEY_E;
                        var nearby = new Vector2f(center.x + (rotate ? 60 : 55) * pixels,
                                center.y + (rotate ? 60 : -5) * pixels);
                        var expected = rotate ? new Vector3f(1, 1, 0) : Vector3f.UNIT_X;
                        Object hit = pick.invoke(tool, camera, nearby, pickType.get(tool));
                        check(expected.equals(hit), "Missed a pointer next to handle " + key + ": " + hit);
                    }
                    camera.setLocation(new Vector3f(0, 0, 30));
                    controller.update(0);
                    check(Math.abs(screenRadius(camera, marker, app.getMainPanel().getHeight()) - 80) < 1f,
                            "Camera motion without pointer input " + key);
                    controller.doKeyPressed(new KeyInputEvent(key, '\0', false, false));
                }
                camera.setLocation(new Vector3f(15, 0, .25f));
                camera.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
                controller.doKeyPressed(new KeyInputEvent(KeyInput.KEY_W, '\0', true, false));
                var move = (MoveTool) toolField.get(controller);
                var moveMarker = (Node) markerField.get(move);
                moveMarker.getParent().updateGeometricState();
                var cone = camera.getScreenCoordinates(moveMarker.getChild("coneX").getWorldTranslation());
                var press = new Vector2f(cone.x, cone.y);
                move.actionPrimary(press, true, explorer, null);
                move.draggedPrimary(press.add(new Vector2f(0, 30)), true, explorer, null);
                move.mouseMoved(press, explorer, null);
                move.draggedPrimary(press.add(new Vector2f(0, 50)), true, explorer, null);
                var position = pivot.getLocalTranslation().clone();
                check(position.x < -.8f && position.y == 0f && position.z == 0f,
                        "Near end-on pointer motion did not move along the red handle: " + position);
                var oldTool = (SceneEditTool) toolField.get(controller);
                var oldMarker = (Node) markerField.get(oldTool);
                check(controller.getSelectionShape() != null, "Selection wireframe was never created");
                controller.setSelectedSpatials(List.of(pivot, survivor));
                branch.removeFromParent();
                controller.update(0);
                check(pivot.getLocalTranslation().equals(position), "Deletion rolled back a detached model");
                check(controller.getSelectedSpatial() == survivor, "Removing a selected child lost its surviving peer");
                check(controller.getSelectedSpatials().equals(List.of(survivor)), "Removed descendants remained selected");
                check(oldMarker.getParent() == null, "Old gesture marker was still attached");
                survivor.removeFromParent();
                controller.update(0);
                var idleTool = (SceneEditTool) toolField.get(controller);
                var idleMarker = (Node) markerField.get(idleTool);
                check(controller.getSelectedSpatial() == null && controller.getSelectedSpatials().isEmpty(),
                        "Detached model remained a transform target");
                check(controller.getSelectionShape() == null, "Selection wireframe remained after deletion");
                check(idleMarker.getCullHint() == Spatial.CullHint.Always, "Handles remained visible with no selection");
                controller.doEditToolDraggedPrimary(press, true, camera);
                controller.doEditToolActivatedPrimary(press, false, camera);
                controller.doUpdateSelection(pivot);
                check(controller.getSelectedSpatial() == null, "Queued selection resurrected a removed model");
                root.attachChild(branch);
                controller.update(0);
                check(controller.getSelectedSpatial() == null, "Restoration resurrected the stale selection");
                controller.doUpdateSelection(pivot);
                controller.update(0);
                check(idleMarker.getLocalCullHint() == Spatial.CullHint.Inherit, "Handles failed to return on reselection");
                var empty = new Vector2f(0, 0);
                controller.doEditToolActivatedPrimary(empty, true, camera);
                check(controller.getSelectedSpatial() == pivot, "Empty press deselected before click release");
                var finishClick = SceneComposerToolController.class.getDeclaredMethod("finishSelectionClick");
                finishClick.setAccessible(true);
                finishClick.invoke(controller);
                controller.doEditToolActivatedPrimary(empty, false, camera);
                check(controller.getSelectedSpatial() == null && controller.getSelectionShape() == null,
                        "Clicking empty space did not clear the active selection");
                com.jme3.gde.scenecomposer.SelectionOutlineSmoke.verify(app);
                com.jme3.gde.scenecomposer.SelectionOutlineSmoke.verifySpotProperties();
                return null;
            }).get(30, TimeUnit.SECONDS);
            verifyMarkerCreationWithDirtyParent(app, controller, root);
            verifyAddedLights(app, controller, explorer, root, toolField, markerField);
            System.out.println("GIZMO_KEYBOARD_AWT_PASS");
            System.out.println("GIZMO_DELETE_AWT_PASS");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (window[0] != null) {
                    window[0].dispose();
                }
            });
            app.stop();
        }
    }

    private static void verifyMarkerCreationWithDirtyParent(SceneApplication app, SceneComposerToolController controller,
            Node root) throws Exception {
        var parentField = SceneComposerToolController.class.getDeclaredField("nonSpatialMarkersNode");
        parentField.setAccessible(true);
        var createMarker = SceneComposerToolController.class.getDeclaredMethod("getOrCreateMarker", AbstractSceneExplorerNode.class);
        createMarker.setAccessible(true);
        var light = new DirectionalLight();
        var node = new JmeDirectionalLight(root, light);
        app.enqueue(() -> {
            root.addLight(light);
            var parent = (Node) parentField.get(controller);
            // Marker creation can run before the tools root processes inherited lights.
            parent.addLight(light);
            parent.removeLight(light);
            try {
                var marker = (Spatial) createMarker.invoke(controller, node);
                check(marker != null && marker.getParent() == parent, "Created light marker was not attached");
            } finally {
                root.removeLight(light);
            }
            return null;
        }).get(15, TimeUnit.SECONDS);
        System.out.println("GIZMO_DIRTY_PARENT_LIGHT_PASS");
    }

    private static void verifyAddedLights(SceneApplication app, SceneComposerToolController controller,
            JmeNode explorerRoot, Node root, java.lang.reflect.Field toolField,
            java.lang.reflect.Field markerField) throws Exception {
        var composerType = SceneComposerTopComponent.class;
        var controllerField = composerType.getDeclaredField("toolController");
        controllerField.setAccessible(true);
        var requestField = composerType.getDeclaredField("currentRequest");
        requestField.setAccessible(true);
        var listenerField = composerType.getDeclaredField("explorerSelectionListener");
        listenerField.setAccessible(true);
        var listener = new java.beans.PropertyChangeListener[1];
        var explorerRef = new java.util.concurrent.atomic.AtomicReference<SceneExplorerTopComponent>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                var explorer = SceneExplorerTopComponent.findInstance();
                explorerRef.set(explorer);
                var composer = new SceneComposerTopComponent();
                var request = new SceneRequest(composer, explorerRoot, null);
                request.setDisplayed(true);
                requestField.set(composer, request);
                controllerField.set(composer, controller);
                listener[0] = (java.beans.PropertyChangeListener) listenerField.get(composer);
                explorer.getExplorerManager().setRootContext(explorerRoot);
                explorer.getExplorerManager().addPropertyChangeListener(listener[0]);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        });
        var explorer = explorerRef.get();
        var location = new Vector3f(3, 2, -4);
        try {
            for (Light light : List.of(new DirectionalLight(), new PointLight(location), new SpotLight(location, Vector3f.UNIT_Z))) {
                app.enqueue(() -> {
                    root.addLight(light);
                    return null;
                }).get(15, TimeUnit.SECONDS);
                SwingUtilities.invokeAndWait(() -> {
                    check(!app.getMainPanel().isFocusOwner(), "Regression requires an unfocused viewport");
                    explorerRoot.refresh(true);
                    explorer.selectAddedObject(explorerRoot, light);
                    var selection = explorer.getExplorerManager().getSelectedNodes();
                    check(selection.length == 1 && selection[0].getLookup().lookup(Light.class) == light,
                            "New light was not selected in the Explorer: " + light.getType());
                });
                app.enqueue(() -> null).get(15, TimeUnit.SECONDS);
                SwingUtilities.invokeAndWait(() -> {});
                app.enqueue(() -> {
                    var selected = controller.getSelectedSpatial();
                    var marker = (Node) markerField.get(toolField.get(controller));
                    check(selected != null && marker.getLocalCullHint() == Spatial.CullHint.Inherit,
                            "Selected light requires a second click or pointer event: " + light.getType());
                    check(selected.getClass().getSimpleName().equals(light.getType() + "LightGizmo"),
                            "Previous transform target survived selection of " + light.getType());
                    check(marker.getLocalTranslation().equals(selected.getWorldTranslation()), "Wrong handle pivot");
                    if (light instanceof PointLight || light instanceof SpotLight) {
                        check(location.equals(marker.getLocalTranslation()), "Handle did not use the drop position");
                    }
                    return null;
                }).get(15, TimeUnit.SECONDS);
                if (light instanceof PointLight || light instanceof SpotLight) {
                    verifyLightDrag(app, controller, explorerRoot, toolField, markerField, light);
                }
            }
        } finally {
            SwingUtilities.invokeAndWait(() ->
                    explorer.getExplorerManager().removePropertyChangeListener(listener[0]));
        }
        System.out.println("GIZMO_ADDED_LIGHT_SELECTION_PASS");
    }

    private static void verifyLightDrag(SceneApplication app, SceneComposerToolController controller,
            JmeNode explorer, java.lang.reflect.Field toolField, java.lang.reflect.Field markerField,
            Light light) throws Exception {
        Vector3f start = lightPosition(light).clone();
        Vector3f moved = app.enqueue(() -> {
            Camera camera = app.getOverlayView().getCamera();
            camera.setLocation(start.add(0, 0, 15));
            camera.lookAt(start, Vector3f.UNIT_Y);
            controller.doKeyPressed(new KeyInputEvent(KeyInput.KEY_W, '\0', true, false));
            var move = (MoveTool) toolField.get(controller);
            var marker = (Node) markerField.get(move);
            marker.getParent().updateGeometricState();
            Vector3f screen = camera.getScreenCoordinates(marker.localToWorld(new Vector3f(1.5f, 0, 0), null));
            Vector2f press = new Vector2f(screen.x, screen.y);
            Vector2f release = press.add(new Vector2f(48, 0));
            move.actionPrimary(press, true, explorer, null);
            move.draggedPrimary(release, true, explorer, null);
            move.actionPrimary(release, false, explorer, null);
            Spatial selected = controller.getSelectedSpatial();
            selected.updateLogicalState(0);
            controller.update(0);
            Vector3f position = lightPosition(light).clone();
            check(position.distance(start) > .1f, "Light position reverted after release: " + light.getType());
            check(position.distance(marker.getLocalTranslation()) < .001f, "Handle and light ended at different positions");
            controller.doKeyPressed(new KeyInputEvent(KeyInput.KEY_W, '\0', false, false));
            return position;
        }).get(15, TimeUnit.SECONDS);

        var history = Lookup.getDefault().lookup(SceneUndoRedoManager.class);
        SwingUtilities.invokeAndWait(history::undo);
        app.enqueue(() -> {
            check(lightPosition(light).distance(start) < .001f, "Undo did not restore the light position");
            return null;
        }).get(15, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(history::redo);
        app.enqueue(() -> {
            check(lightPosition(light).distance(moved) < .001f, "Redo did not restore the dragged light position");
            return null;
        }).get(15, TimeUnit.SECONDS);
        System.out.println("GIZMO_LIGHT_DRAG_PASS " + light.getType());
    }

    private static Vector3f lightPosition(Light light) {
        return light instanceof PointLight point ? point.getPosition() : ((SpotLight) light).getPosition();
    }

    private static float screenRadius(Camera camera, Node marker, int logicalHeight) {
        Vector3f pivot = marker.getLocalTranslation();
        var tip = camera.getScreenCoordinates(pivot.add(0, marker.getLocalScale().y * 2f, 0));
        return Math.abs(tip.y - camera.getScreenCoordinates(pivot).y) * logicalHeight / camera.getHeight();
    }

    private static void check(boolean condition, String text) {
        if (!condition) {
            throw new AssertionError(text);
        }
    }
}

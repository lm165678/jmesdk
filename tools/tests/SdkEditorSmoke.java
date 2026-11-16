package com.jme3.gde.scenecomposer;

import com.jme3.input.KeyInput;
import com.jme3.input.event.KeyInputEvent;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Sphere;
import com.jme3.scene.plugins.sdf.*;
import java.util.Arrays;

/** Run with tools/verify-editor.ps1 after buildSdk. */
public final class SdkEditorSmoke {
    private static KeyInputEvent key(int code, boolean pressed) {
        return new KeyInputEvent(code, '\0', pressed, false);
    }
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
    public static void main(String[] args) throws Exception {
        EditorShortcutPolicy policy = new EditorShortcutPolicy();
        require(policy.accept(key(KeyInput.KEY_LCONTROL, true)) == 0, "Ctrl");
        for (int code : new int[]{KeyInput.KEY_C, KeyInput.KEY_V, KeyInput.KEY_X, KeyInput.KEY_S,
                KeyInput.KEY_Z, KeyInput.KEY_Y, KeyInput.KEY_Q, KeyInput.KEY_W, KeyInput.KEY_E, KeyInput.KEY_R}) {
            require(policy.accept(key(code, true)) == 0, "Modified key must not transform " + code);
            require(policy.accept(key(code, false)) == 0, "Release must not transform");
        }
        policy.accept(key(KeyInput.KEY_LCONTROL, false));
        for (int code : new int[]{KeyInput.KEY_Q, KeyInput.KEY_W, KeyInput.KEY_E, KeyInput.KEY_R}) {
            require(policy.accept(key(code, true)) == code, "Tool switch");
            require(policy.accept(key(code, true)) == 0, "Auto repeat");
            policy.accept(key(code, false));
        }
        for (int code : new int[]{KeyInput.KEY_S, KeyInput.KEY_D, KeyInput.KEY_X}) {
            require(policy.accept(key(code, true)) == 0, "Legacy modal shortcut disabled");
        }
        policy.reset();
        require(policy.accept(key(KeyInput.KEY_G, true)) == KeyInput.KEY_G, "G toggles editor helpers");
        require(policy.accept(key(KeyInput.KEY_G, true)) == 0, "Held G must not flicker helpers");
        policy.accept(key(KeyInput.KEY_G, false));
        policy.reset();
        System.out.println("SDK_SHORTCUT_POLICY_PASS");

        EditorFlyNavigation flight = new EditorFlyNavigation();
        Camera camera = new Camera(800, 600);
        camera.setLocation(Vector3f.ZERO);
        Vector3f focus = new Vector3f(0, 0, 5);
        flight.key(key(KeyInput.KEY_W, true));
        flight.update(camera, focus, 1);
        require(camera.getLocation().equals(Vector3f.ZERO), "No right mouse: no flight");
        flight.setFlying(true);
        flight.update(camera, focus, 1);
        require(Math.abs(camera.getLocation().length() - 10) < .001f, "Frame independent flight speed");
        Vector3f before = camera.getLocation().clone();
        flight.look(camera, focus, "MouseAxisX", .1f);
        require(before.equals(camera.getLocation()), "Look must rotate in place");
        flight.setFlying(false);
        flight.setFlying(true);
        flight.update(camera, focus, 1);
        require(before.equals(camera.getLocation()), "Right release must clear held keys");
        flight.key(key(KeyInput.KEY_W, true));
        flight.key(key(KeyInput.KEY_D, true));
        flight.update(camera, focus, 1);
        require(Math.abs(camera.getLocation().distance(before) - 10) < .001f, "Diagonal speed normalized");
        before.set(camera.getLocation());
        flight.reset();
        flight.update(camera, focus, 1);
        require(before.equals(camera.getLocation()), "Focus loss stops movement");
        camera.lookAtDirection(new Vector3f(0, -1, 1).normalizeLocal(), Vector3f.UNIT_Y);
        flight.key(key(KeyInput.KEY_Q, true));
        flight.update(camera, focus, 1);
        require(before.equals(camera.getLocation()), "Vertical keys require right mouse");
        flight.setFlying(true);
        Vector3f focusBefore = focus.clone();
        flight.update(camera, focus, 1);
        require(camera.getLocation().distance(before.add(0, 10, 0)) < .001f,
                "Q ascends on world Y even with a pitched camera");
        require(focus.distance(focusBefore.add(0, 10, 0)) < .001f, "Focus follows vertical movement");
        flight.key(key(KeyInput.KEY_Q, false));
        flight.key(key(KeyInput.KEY_Z, true));
        flight.key(key(KeyInput.KEY_LSHIFT, true));
        before.set(camera.getLocation());
        flight.update(camera, focus, 1);
        require(camera.getLocation().distance(before.add(0, -40, 0)) < .001f,
                "Shift accelerates Z descent");
        flight.key(key(KeyInput.KEY_Q, true));
        before.set(camera.getLocation());
        flight.update(camera, focus, 1);
        require(before.equals(camera.getLocation()), "Opposite vertical keys cancel");
        flight.setFlying(false);
        flight.setFlying(true);
        flight.update(camera, focus, 1);
        require(before.equals(camera.getLocation()), "Release clears vertical keys and boost");
        flight.reset();
        System.out.println("SDK_FLY_NAVIGATION_PASS");

        Node model = new Node("Model");
        Geometry geometry = new Geometry("Sphere", new Sphere(16, 24, 1));
        model.attachChild(geometry);
        model.updateGeometricState();
        byte[] stamp = ModelBakeSupport.fingerprint(model);
        Node copy = (Node) ModelBakeSupport.copy(model);
        ModelBakeSupport.normalsAndTangents(copy, true, true);
        require(((Geometry) copy.getChild(0)).getMesh().getBuffer(VertexBuffer.Type.Tangent) != null, "Tangents");
        ModelBakeSupport.lod(copy, new float[]{.5f, .25f});
        require(((Geometry) copy.getChild(0)).getMesh().getNumLodLevels() == 3, "LOD levels");
        require(Arrays.equals(stamp, ModelBakeSupport.fingerprint(model)), "Baking must leave live source unchanged");
        SdfVolume sdf = SdfGenerator.generate(copy, new SdfSettings(16, 16, 16, .05f,
                SdfSettings.SignMode.EVEN_ODD), value -> true);
        require(sdf.sample(Vector3f.ZERO) < 0, "Signed SDF interior");
        require(model.getUserData(SdfVolume.USER_DATA_KEY) == null, "SDF should attach only at commit");
        System.out.println("SDK_MODEL_CPU_BAKE_PASS");
        System.out.println("SDK_EDITOR_SMOKE_PASS");
    }
}

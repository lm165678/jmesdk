package com.jme3.gde.scenecomposer.gizmo;

import com.jme3.asset.AssetManager;
import com.jme3.gde.scenecomposer.gizmo.light.ScreenSizeControl;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Start;
import com.jme3.scene.control.AbstractControl;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Cylinder;

/** Displays the camera origin and solid forward (+Z) and up (+Y) axes.
 * 第一步：添加 Start，黄色方框表示原点，相机图标朝向视口。
 * 第二步：青蓝色表示前方，绿色表示上方；按 G 隐藏，再按 G 恢复。
 * All geometry belongs to the editor tools viewport and is never saved to J3O.
 */
final class StartGizmo {
    private StartGizmo() {
    }

    static Node create(AssetManager assets, Start start) {
        Node marker = new Node("Start marker");
        marker.setShadowMode(RenderQueue.ShadowMode.Off);
        Node icon = new Node("Start axes");
        icon.attachChild(makeGeometry(assets, "Position", new Box(.085f, .085f, .085f), ColorRGBA.Yellow));
        Node forward = makeArrow(assets, "Forward", 1f, ColorRGBA.Cyan);
        forward.rotate(com.jme3.math.FastMath.HALF_PI, 0f, 0f);
        icon.attachChild(forward);
        icon.attachChild(makeArrow(assets, "Up", .65f, ColorRGBA.Green));
        icon.addControl(new ScreenSizeControl(64f, 1f));
        marker.attachChild(icon);
        Node cameraIcon = new Node("Start camera icon");
        cameraIcon.addControl(new com.jme3.scene.control.BillboardControl());
        Geometry camera = GizmoIcon.create(assets, "Start camera", "camera.svg",
                "com/jme3/gde/scenecomposer/gizmo/icons/camera.png", false);
        // Offset in screen space so the camera does not cover the yellow origin.
        camera.removeControl(ScreenSizeControl.class);
        camera.setLocalTranslation(-.5f, .35f, 0f);
        camera.addControl(new ScreenSizeControl(36f, 1f));
        cameraIcon.attachChild(camera);
        marker.attachChild(cameraIcon);
        marker.addControl(new AbstractControl() {
            private final Vector3f translation = new Vector3f();
            private final Quaternion rotation = new Quaternion();

            @Override
            protected void controlUpdate(float tpf) {
                Node parent = marker.getParent();
                if (parent != null) {
                    parent.worldToLocal(start.getWorldTranslation(), translation);
                    rotation.set(parent.getWorldRotation()).inverseLocal().multLocal(start.getWorldRotation());
                    marker.setLocalTranslation(translation);
                    marker.setLocalRotation(rotation);
                }
            }

            @Override
            protected void controlRender(RenderManager manager, ViewPort view) {
            }
        });
        return marker;
    }

    private static Geometry makeGeometry(AssetManager assets, String name, Mesh mesh, ColorRGBA color) {
        Material material = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", color);
        material.getAdditionalRenderState().setLineWidth(2f);
        Geometry geometry = new Geometry(name, mesh);
        geometry.setMaterial(material);
        return geometry;
    }

    // Match the light arrow's pixel thickness while keeping Start's shorter axes.
    private static Node makeArrow(AssetManager assets, String name, float length, ColorRGBA color) {
        float ratio = 108f / 64f;
        float headLength = .19f * ratio;
        float shaftLength = length - headLength;
        Node arrow = new Node(name);
        Geometry shaft = makeGeometry(assets, "shaft",
                new Cylinder(2, 12, .018f * ratio, shaftLength, true), color);
        shaft.rotate(-com.jme3.math.FastMath.HALF_PI, 0f, 0f);
        shaft.setLocalTranslation(0f, shaftLength * .5f, 0f);
        arrow.attachChild(shaft);
        Geometry tip = makeGeometry(assets, "tip",
                GizmoFactory.createCone(12, .044f * ratio, headLength), color);
        tip.setLocalTranslation(0f, shaftLength, 0f);
        arrow.attachChild(tip);
        return arrow;
    }
}

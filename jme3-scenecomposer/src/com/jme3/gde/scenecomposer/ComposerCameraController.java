/*
 * Copyright (c) 2009-2010 jMonkeyEngine All rights reserved. <p/>
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 *  * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer. <p/> * Redistributions
 * in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other
 * materials provided with the distribution. <p/> * Neither the name of
 * 'jMonkeyEngine' nor the names of its contributors may be used to endorse or
 * promote products derived from this software without specific prior written
 * permission. <p/> THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT
 * NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.gde.scenecomposer;

import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.scene.controller.AbstractCameraController;
import com.jme3.gde.core.sceneexplorer.nodes.JmeNode;
import com.jme3.input.event.KeyInputEvent;
import com.jme3.math.Vector2f;
import com.jme3.renderer.Camera;

/**
 * Scene editing input: left clicks select, left drags orbit the camera, and
 * drags starting on transform handles edit the object instead. A four-logical-
 * pixel threshold avoids rotating the camera during a click. Right mouse looks around.
 * Hold right mouse and WASD to fly; Q ascends and Z descends along world Y
 * (Shift accelerates). Middle mouse retains
 * orbit and Shift-middle pan. Q selects; W/E/R select move/rotate/scale handles. Tool shortcuts are disabled
 * during right-button flight and primary drags; T has no Rect tool binding.
 * Input and camera changes run on the render thread. Focus loss and disable
 * clear held keys and restore cursor visibility; right mouse captures/hides the
 * pointer for continuous flight. The translucent viewport buttons share Q/W/E/R state.
 * Releasing right mouse stops flight immediately. Double-click a picked object
 * to frame its bounds; drags and transform handles do not trigger framing.
 * <pre>
 * 第一步：左键点击物体；按住左键拖动旋转镜头；按 Q 选择，按 W/E/R 后拖动轴只变换物体。
 * 第二步：按住右键，移动鼠标转向，配合 WASD 漫游，Q 沿世界竖直方向上升，Z 下降，Shift 加速。
 * 第三步：松开右键停止漫游；工具栏 Help 可再次查看操作说明。
 * </pre>
 */
public class ComposerCameraController extends AbstractCameraController {
    private SceneComposerToolController toolController;
    private TransformToolOverlay overlay;
    private boolean overlayPressed;
    private float primaryStartX;
    private float primaryStartY;
    private boolean primaryOrbit;
    private long lastClickTime;
    private com.jme3.scene.Spatial lastClickTarget;
    private float lastClickX;
    private float lastClickY;
    private boolean pointerCaptured;
    private boolean previousCursorVisible;

    @Override
    public void disable() {
        super.disable();
        SceneApplication.getApplication().enqueue(() -> {
            if (overlay != null) {
                overlay.close();
                overlay = null;
            }
            return null;
        });
    }

    private void capturePointer(boolean capture) {
        if (capture && !pointerCaptured) {
            previousCursorVisible = inputManager.isCursorVisible();
            inputManager.setCursorVisible(false);
            pointerCaptured = true;
        } else if (!capture && pointerCaptured) {
            inputManager.setCursorVisible(previousCursorVisible);
            pointerCaptured = false;
        }
    }

    private final EditorFlyNavigation navigation = new EditorFlyNavigation();

    public ComposerCameraController(Camera cam, JmeNode rootNode) {
        super(cam, SceneApplication.getApplication().getInputManager());
    }

    public void setToolController(SceneComposerToolController controller) {
        toolController = controller;
    }

    public boolean isToolUsesCameraControls() {
        return false;
    }

    public Camera getCamera() {
        return cam;
    }

    @Override
    public void onKeyEvent(KeyInputEvent event) {
        super.onKeyEvent(event);
        navigation.key(event);
        if (toolController != null) {
            toolController.doKeyPressed(event, !navigation.isFlying() && !buttonDownL && !overlayPressed);
            if (event.isPressed() && event.getKeyCode() == com.jme3.input.KeyInput.KEY_ESCAPE && buttonDownL) {
                toolController.doEditToolActivatedSecondary(new Vector2f(mouseX, mouseY), true, cam);
                buttonDownL = false;
            }
        }
    }

    @Override
    public void onAction(String name, boolean pressed, float tpf) {
        if ("MouseButtonRight".equals(name)) {
            if (pressed && buttonDownL) {
                checkClick(0, false);
                buttonDownL = false;
            }
            capturePointer(pressed);
            navigation.setFlying(pressed);
            buttonDownR = pressed;
        } else if ("MouseButtonLeft".equals(name)) {
            if (!pressed && overlayPressed) {
                overlayPressed = false;
                return;
            }
            if (pressed && !navigation.isFlying() && overlay != null && toolController != null) {
                int key = overlay.hit(mouseX, mouseY);
                if (key != -1) {
                    toolController.selectTransform(key);
                    overlayPressed = true;
                    return;
                }
            }
            if (!navigation.isFlying()) {
                if (pressed) {
                    primaryStartX = mouseX;
                    primaryStartY = mouseY;
                }
                boolean click = !pressed && buttonDownL && !primaryOrbit
                        && toolController != null && toolController.allowsPrimaryOrbit();
                if (click) {
                    toolController.finishSelectionClick();
                }
                primaryOrbit = false;
                buttonDownL = pressed;
                checkClick(0, pressed);
                if (click) {
                    focusDoubleClick();
                } else if (!pressed) {
                    lastClickTarget = null;
                }
            }
        } else if ("MouseButtonMiddle".equals(name)) {
            buttonDownM = pressed;
        }
    }

    private void focusDoubleClick() {
        com.jme3.scene.Spatial target = toolController.getOrbitPickedSpatial();
        long now = System.nanoTime();
        int width = SceneApplication.getApplication().getMainPanel().getWidth();
        float scale = width > 0 ? (float) cam.getWidth() / width : 1;
        float dx = mouseX - lastClickX;
        float dy = mouseY - lastClickY;
        if (target != null && target == lastClickTarget
                && now - lastClickTime < 500_000_000L
                && dx * dx + dy * dy <= 25 * scale * scale) {
            com.jme3.scene.Spatial root = target;
            while (root.getParent() != null) {
                root = root.getParent();
            }
            root.updateGeometricState();
            com.jme3.bounding.BoundingVolume bound = target.getWorldBound();
            com.jme3.math.Vector3f center = bound == null
                    ? target.getWorldTranslation() : bound.getCenter();
            float radius = bound instanceof com.jme3.bounding.BoundingBox box
                    ? box.getExtent(null).length()
                    : bound instanceof com.jme3.bounding.BoundingSphere sphere ? sphere.getRadius() : 1;
            radius = Math.max(0.1f, radius);
            if (com.jme3.math.Vector3f.isValidVector(center) && Float.isFinite(radius)) {
                float halfAngle = (float) Math.atan(Math.min(Math.abs(cam.getFrustumTop()),
                        Math.abs(cam.getFrustumRight())) / cam.getFrustumNear());
                float distance = radius * 1.15f / Math.max(0.01f, (float) Math.sin(halfAngle));
                cam.setLocation(center.subtract(cam.getDirection().mult(distance)));
                if (cam.isParallelProjection()) {
                    float aspect = (float) cam.getWidth() / Math.max(1, cam.getHeight());
                    float height = radius * 1.15f / Math.min(1f, aspect);
                    cam.setFrustum(cam.getFrustumNear(), cam.getFrustumFar(),
                            -height * aspect, height * aspect, height, -height);
                }
                doSetCamFocus(center, false);
            }
            lastClickTarget = null;
            lastClickTime = 0;
        } else {
            lastClickTarget = target;
            lastClickTime = now;
            lastClickX = mouseX;
            lastClickY = mouseY;
        }
    }

    @Override
    public void onAnalog(String name, float value, float tpf) {
        if (navigation.isFlying()) {
            navigation.look(cam, focus, name, value);
        } else {
            if (buttonDownL && !overlayPressed && !primaryOrbit
                    && (toolController == null || toolController.allowsPrimaryOrbit())) {
                int width = SceneApplication.getApplication().getMainPanel().getWidth();
                float scale = width > 0 ? (float) cam.getWidth() / width : 1;
                float dx = mouseX - primaryStartX;
                float dy = mouseY - primaryStartY;
                float threshold = 4 * scale;
                primaryOrbit = dx * dx + dy * dy >= threshold * threshold;
            }
            super.onAnalog(name, value, tpf);
        }
    }

    @Override
    public void update(float tpf) {
        if (overlay == null) {
            overlay = new TransformToolOverlay(SceneApplication.getApplication());
        }
        overlay.update(cam, toolController == null ? -1 : toolController.activeTransform(),
                toolController == null ? Float.NaN : toolController.rotationDragDegrees());
        navigation.update(cam, focus, tpf);
        if (!navigation.isFlying() && moved && toolController != null) {
            if (buttonDownL) {
                if (!primaryOrbit) {
                    checkDragged(0, true);
                }
            } else if (!buttonDownM) {
                checkMoved();
            }
        }
        moved = false;
    }

    @Override
    public void resetInputState() {
        if (buttonDownL && toolController != null) {
            checkClick(0, false);
        }
        capturePointer(false);
        overlayPressed = false;
        lastClickTarget = null;
        lastClickTime = 0;
        primaryOrbit = false;
        navigation.reset();
        if (toolController != null) {
            toolController.resetShortcutState();
        }
        super.resetInputState();
    }

    @Override
    public void checkClick(int button, boolean pressed) {
        if (button == 0 && toolController != null) {
            toolController.doEditToolActivatedPrimary(new Vector2f(mouseX, mouseY), pressed, cam);
        }
    }

    @Override
    protected void checkDragged(int button, boolean pressed) {
        if (button == 0 && toolController != null) {
            toolController.doEditToolDraggedPrimary(new Vector2f(mouseX, mouseY), pressed, cam);
        }
    }

    @Override
    protected void checkMoved() {
        toolController.doEditToolMoved(new Vector2f(mouseX, mouseY), cam);
    }

    @Override
    public boolean useCameraControls() {
        return buttonDownL && primaryOrbit && !overlayPressed && !navigation.isFlying();
    }
}

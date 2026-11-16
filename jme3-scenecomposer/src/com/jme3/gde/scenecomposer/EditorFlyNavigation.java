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

import com.jme3.input.KeyInput;
import com.jme3.input.event.KeyInputEvent;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;

/**
 * Render-thread flight state used only by the scene editor. Right mouse gates
 * movement: WASD follows the camera, Q/Z ascends/descends along world Y.
 * Toolbar Speed sets world units/second (default 10); Shift multiplies it by four. Combined movement
 * is normalized; releasing right mouse or losing focus clears all held inputs.
 */
final class EditorFlyNavigation {
    private boolean flying;
    private boolean forward, backward, left, right, shift;
    private boolean ascend;
    private boolean descend;

    boolean isFlying() {
        return flying;
    }

    void setFlying(boolean value) {
        if (!value) {
            reset();
        }
        flying = value;
    }

    void reset() {
        flying = false;
        forward = false;
        backward = false;
        left = false;
        right = false;
        shift = false;
        ascend = false;
        descend = false;
    }

    void key(KeyInputEvent event) {
        boolean down = event.isPressed();
        switch (event.getKeyCode()) {
            case KeyInput.KEY_W:
                forward = down;
                break;
            case KeyInput.KEY_S:
                backward = down;
                break;
            case KeyInput.KEY_A:
                left = down;
                break;
            case KeyInput.KEY_D:
                right = down;
                break;
            case KeyInput.KEY_Z:
                descend = down;
                break;
            case KeyInput.KEY_Q:
                ascend = down;
                break;
            case KeyInput.KEY_LSHIFT:
            case KeyInput.KEY_RSHIFT:
                shift = down;
                break;
            default:
                 break;
        }
    }

    void update(Camera camera, Vector3f focus, float tpf) {
        if (!flying) {
            return;
        }
        Vector3f motion = camera.getDirection().mult((forward ? 1 : 0) - (backward ? 1 : 0));
        motion.addLocal(camera.getLeft().mult((left ? 1 : 0) - (right ? 1 : 0)));
        motion.y += (ascend ? 1 : 0) - (descend ? 1 : 0);
        if (motion.lengthSquared() > 0) {
            motion.normalizeLocal().multLocal(com.jme3.gde.core.scene.SceneApplication.getApplication().getEditorFlySpeed()
                    * (shift ? 4f : 1f) * tpf);
            camera.setLocation(camera.getLocation().add(motion));
            focus.addLocal(motion);
        }
    }

    void look(Camera camera, Vector3f focus, String axis, float value) {
        Vector3f rotationAxis;
        float angle;
        switch (axis) {
            case "MouseAxisX":
                rotationAxis = Vector3f.UNIT_Y;
                angle = -value * 2.5f;
                break;
            case "MouseAxisX-":
                rotationAxis = Vector3f.UNIT_Y;
                angle = value * 2.5f;
                break;
            case "MouseAxisY":
                rotationAxis = camera.getLeft();
                angle = -value * 2.5f;
                break;
            case "MouseAxisY-":
                rotationAxis = camera.getLeft();
                angle = value * 2.5f;
                break;
            default:
                 return;
        }
        if (axis.startsWith("MouseAxisY")) {
            float elevation = -FastMath.asin(camera.getDirection().y);
            float limit = FastMath.HALF_PI - .01f;
            angle = FastMath.clamp(elevation + angle, -limit, limit) - elevation;
        }
        float distance = Math.max(1f, camera.getLocation().distance(focus));
        camera.setRotation(new Quaternion().fromAngleAxis(angle, rotationAxis).mult(camera.getRotation()));
        focus.set(camera.getLocation()).addLocal(camera.getDirection().mult(distance));
    }
}

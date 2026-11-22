/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.jme3.gde.scenecomposer.gizmo.audio;

import com.jme3.asset.AssetManager;
import com.jme3.audio.AudioNode;
import com.jme3.gde.core.sceneexplorer.nodes.JmeAudioNode;
import com.jme3.gde.scenecomposer.gizmo.GizmoIcon;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.control.BillboardControl;

/**
 *
 * @author dokthar
 */
public class AudioGizmoFactory {

    public static Spatial createGizmo(AssetManager assetManager, JmeAudioNode node) {
        AudioNode audio = node.getLookup().lookup(AudioNode.class);
        if (audio == null) {
            return null;
        }
        Node gizmo = new Node("Audio node Gizmo");
        gizmo.attachChild(createAudioMarker(assetManager));
        gizmo.addControl(new AudioMarkerControl(audio));
        return gizmo;
    }
    
    /**
     * Updates the marker's position whenever the audio node has moved. It is
     * also a BillboardControl, so this marker always faces the camera
     */
    protected static class AudioMarkerControl extends BillboardControl {

        private final AudioNode audio;
        private final Vector3f position = new Vector3f();

        AudioMarkerControl(AudioNode audio) {
            this.audio = audio;
        }

        @Override
        protected void controlUpdate(float f) {
            super.controlUpdate(f);
            Spatial marker = getSpatial();
            if (marker != null && marker.getParent() != null) {
                marker.getParent().worldToLocal(audio.getWorldTranslation(), position);
                if (!marker.getLocalTranslation().equals(position)) {
                    marker.setLocalTranslation(position);
                }
            }
        }
    }

    /**
     * A marker on the screen that shows where an audio node is.
     */
    protected static Geometry createAudioMarker(AssetManager assetManager) {
        return GizmoIcon.create(assetManager, "Audio source", "volume-2.svg",
                "com/jme3/gde/scenecomposer/audionode.gif", true);
    }
}

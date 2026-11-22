/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.jme3.gde.scenecomposer.gizmo;

import com.jme3.asset.AssetManager;
import com.jme3.gde.core.sceneexplorer.nodes.AbstractSceneExplorerNode;
import com.jme3.gde.core.sceneexplorer.nodes.JmeAudioNode;
import com.jme3.gde.core.sceneexplorer.nodes.JmeLight;
import com.jme3.gde.scenecomposer.gizmo.audio.AudioGizmoFactory;
import com.jme3.gde.scenecomposer.gizmo.light.LightGizmoFactory;
import com.jme3.math.FastMath;
import com.jme3.scene.Mesh;
import com.jme3.scene.Spatial;
import com.jme3.scene.Start;
import com.jme3.scene.VertexBuffer.Type;

/**
 *
 * @author dokthar
 */
public class GizmoFactory {

    /** Builds a capped arrow tip with its base at zero and its point along +Y. */
    public static Mesh createCone(int sides, float radius, float height) {
        float[] positions = new float[3 * (sides + 2)];
        short[] indices = new short[6 * sides];
        short tip = (short) sides;
        short base = (short) (sides + 1);
        positions[3 * tip + 1] = height;
        for (short i = 0; i < sides; i++) {
            float angle = FastMath.TWO_PI * i / sides;
            positions[3 * i] = radius * FastMath.cos(angle);
            positions[3 * i + 2] = radius * FastMath.sin(angle);
            short next = (short) ((i + 1) % sides);
            int face = 6 * i;
            indices[face] = tip;
            indices[face + 1] = next;
            indices[face + 2] = i;
            indices[face + 3] = i;
            indices[face + 4] = next;
            indices[face + 5] = base;
        }
        Mesh mesh = new Mesh();
        mesh.setBuffer(Type.Position, 3, positions);
        mesh.setBuffer(Type.Index, 3, indices);
        mesh.updateBound();
        return mesh;
    }

    public static Spatial createGizmo(AssetManager assetManager, AbstractSceneExplorerNode node) {
        com.jme3.environment.gi.GiProbeVolume volume = node.getLookup().lookup(com.jme3.environment.gi.GiProbeVolume.class);
        if (volume != null) {
            return GiProbeGizmo.create(assetManager, volume);
        }
        Start start = node.getLookup().lookup(Start.class);
        if (start != null) {
            return StartGizmo.create(assetManager, start);
        }
        if (node instanceof JmeLight) {
            return LightGizmoFactory.createGizmo(assetManager, (JmeLight) node);
        } else if (node instanceof JmeAudioNode) {
            return AudioGizmoFactory.createGizmo(assetManager, (JmeAudioNode) node);
        }

        return null;
    }

}

package com.jme3.gde.scenecomposer.gizmo;

import com.jme3.asset.AssetManager;
import com.jme3.environment.gi.GiProbeData;
import com.jme3.environment.gi.GiProbeVolume;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.*;
import com.jme3.scene.control.AbstractControl;
import com.jme3.scene.debug.WireBox;
import java.nio.FloatBuffer;

/** Tool-only adaptive coverage visualization; never saved with source geometry.
 * Uses resident streaming payloads rather than losing relocation after export.
 * Light blue=valid coarse, green=valid fine, yellow=physically relocated, red=invalid,
 * gray=unbaked/not resident, orange=surface probes. The fine grid is capped at 4096 displayed points.
 */
final class GiProbeGizmo {
    private GiProbeGizmo() {
    }

    static Spatial create(AssetManager assets, GiProbeVolume volume) {
        Node gizmo = new Node("GI volume coverage");
        gizmo.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);
        // Children attached during a logical update may miss that frame's child
        // update traversal. Every attached geometry must already have a mesh.
        Vector3f initialCoverage = volume.getCoverage();
        Geometry bounds = new Geometry("Coverage", new WireBox(initialCoverage.x * 0.5f,
                initialCoverage.y * 0.5f, initialCoverage.z * 0.5f));
        bounds.setLocalTranslation(initialCoverage.mult(0.5f));
        Geometry probes = new Geometry("Probe positions");
        Geometry relocations = new Geometry("Probe relocation vectors");
        Material lines = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        lines.setColor("Color", new ColorRGBA(0.2f, 0.9f, 1, 1));
        bounds.setMaterial(lines);
        Material points = lines.clone();
        points.setFloat("PointSize", 4);
        points.setColor("Color", ColorRGBA.White);
        points.setBoolean("VertexColor", true);
        probes.setMaterial(points);
        Material movedLines = lines.clone();
        movedLines.setColor("Color", ColorRGBA.Yellow);
        relocations.setMaterial(movedLines);
        gizmo.attachChild(bounds);
        gizmo.addControl(new AbstractControl() {
            private Vector3f previousCoverage;
            private Vector3f previousCounts;
            private GiProbeData previousData;
            private Object previousDirectory;
            private com.jme3.gde.core.scene.SceneApplication.ProbeDisplayMode previousMode;
            private java.util.List<com.jme3.environment.gi.GiProbeStreamer.ResidentBrick> previousBricks = java.util.List.of();
            private Vector3f previousScale;
            private final java.util.Map<GiProbeVolume, Spatial> refinements = new java.util.IdentityHashMap<>();

            @Override
            protected void controlUpdate(float tpf) {
                var application = com.jme3.gde.core.scene.SceneApplication.getApplication();
                boolean refined = Boolean.TRUE.equals(volume.getUserData("jme.gi.adaptive.refinement"));
                gizmo.setCullHint(application.isGiVolumesVisible()
                        && !(refined && application.getProbeDisplayMode() == com.jme3.gde.core.scene.SceneApplication.ProbeDisplayMode.COARSE)
                        ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
                Node parent = gizmo.getParent();
                gizmo.setLocalTranslation(parent == null ? volume.getWorldTranslation()
                        : parent.worldToLocal(volume.getWorldTranslation(), new Vector3f()));
                gizmo.setLocalScale(parent == null ? volume.getWorldScale()
                        : volume.getWorldScale().divide(parent.getWorldScale()));
                java.util.Set<GiProbeVolume> current = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                for (Spatial child : volume.getChildren()) {
                    if (child instanceof GiProbeVolume fine
                            && Boolean.TRUE.equals(child.getUserData("jme.gi.adaptive.refinement"))) {
                        current.add(fine);
                        if (!refinements.containsKey(fine)) {
                            Spatial display = create(assets, fine);
                            refinements.put(fine, display);
                            gizmo.attachChild(display);
                        }
                    }
                }
                refinements.entrySet().removeIf(entry -> {
                    if (!current.contains(entry.getKey())) {
                        entry.getValue().removeFromParent();
                        return true;
                    }
                    return false;
                });
                Vector3f coverage = volume.getCoverage();
                Vector3f counts = volume.getProbeCounts();
                GiProbeData data = com.jme3.gde.core.scene.SceneApplication.getApplication().getGiProbePreviewData(volume);
                var app = com.jme3.gde.core.scene.SceneApplication.getApplication();
                var mode = app.getProbeDisplayMode();
                var bricks = app.getGiProbePreviewBricks(volume);
                if (coverage.equals(previousCoverage) && counts.equals(previousCounts) && data == previousData
                        && previousDirectory == volume.getStreamingData() && mode == previousMode
                        && bricks.equals(previousBricks) && volume.getWorldScale().equals(previousScale)) {
                    return;
                }
                previousCoverage = coverage;
                previousCounts = counts;
                previousData = data;
                previousDirectory = volume.getStreamingData();
                previousMode = mode;
                previousBricks = bricks;
                previousScale = volume.getWorldScale().clone();
                bounds.setMesh(new WireBox(coverage.x * 0.5f, coverage.y * 0.5f, coverage.z * 0.5f));
                bounds.setLocalTranslation(coverage.mult(0.5f));
                GiProbeMarkers.Buffers markers = GiProbeMarkers.build(volume, data, bricks, mode);
                if (mode == com.jme3.gde.core.scene.SceneApplication.ProbeDisplayMode.FINE
                        && !refined && !refinements.isEmpty() && volume.getStreamingData() == null) {
                    markers.positions().limit(0);
                    markers.colors().limit(0);
                    markers.movement().limit(0);
                }
                FloatBuffer positions = markers.positions();
                FloatBuffer colors = markers.colors();
                FloatBuffer movement = markers.movement();
                Mesh mesh = new Mesh();
                mesh.setMode(Mesh.Mode.Points);
                mesh.setBuffer(VertexBuffer.Type.Position, 3, positions);
                mesh.setBuffer(VertexBuffer.Type.Color, 4, colors);
                mesh.updateBound();
                probes.setMesh(mesh);
                if (probes.getParent() == null) {
                    gizmo.attachChild(probes);
                }
                relocations.setCullHint(movement.remaining() == 0 ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
                if (movement.remaining() > 0) {
                    Mesh vectors = new Mesh();
                    vectors.setMode(Mesh.Mode.Lines);
                    vectors.setBuffer(VertexBuffer.Type.Position, 3, movement);
                    vectors.updateBound();
                    relocations.setMesh(vectors);
                    if (relocations.getParent() == null) {
                        gizmo.attachChild(relocations);
                    }
                }
            }

            @Override
            protected void controlRender(RenderManager manager, ViewPort viewPort) {
            }
        });
        return gizmo;
    }
}

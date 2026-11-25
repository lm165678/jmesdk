package com.jme3.gde.scenecomposer;

import com.jme3.environment.gi.*;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.gde.core.assets.LightmapUvGenerator;
import com.jme3.scene.*;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.shape.Box;
import com.jme3.scene.mesh.MorphTarget;
import java.util.concurrent.CancellationException;

/** Subtree UV2, excluded deformables, Clear, undo/redo, cancellation and J3O. */
public final class LightmapUvSubtreeSmoke {
    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
    public static void main(String[] args) throws Exception {
        Node root = new Node("SceneRoot");
        Mesh skin = new Box(1, 1, 1);
        skin.setBuffer(Type.BoneIndex, 4, new byte[skin.getVertexCount() * 4]);
        skin.setBuffer(Type.BoneWeight, 4, new float[skin.getVertexCount() * 4]);
        skin.setBuffer(Type.TexCoord2, 2, new float[skin.getVertexCount() * 2]);
        Geometry actor = new Geometry("Actor", skin);
        actor.setMobility(Mobility.Dynamic);
        var oldActorUv = skin.getBuffer(Type.TexCoord2);
        var oldWeights = skin.getBuffer(Type.BoneWeight);
        ProbeLightmapControl actorMap = new ProbeLightmapControl("keep.j3o", actor.getWorldTransform(), 100);
        actor.addControl(actorMap);
        Mesh morphMesh = new Box(1, 1, 1);
        morphMesh.addMorphTarget(new MorphTarget("Face"));
        Geometry morph = new Geometry("Morph", morphMesh);
        Geometry rigid = new Geometry("Rigid", new Box(1, 1, 1));
        Geometry dynamic = new Geometry("Dynamic rigid", new Box(1, 1, 1));
        dynamic.setMobility(Mobility.Dynamic);
        root.attachChild(actor);
        root.attachChild(morph);
        root.attachChild(rigid);
        root.attachChild(dynamic);
        Node helpers = new Node();
        root.attachChild(helpers);
        Geometry sky = new Geometry("Sky", new Box(1, 1, 1));
        sky.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Sky);
        helpers.attachChild(sky);
        GiProbeVolume volume = new GiProbeVolume();
        volume.attachChild(new Geometry("Probe helper", new Box(1, 1, 1)));
        helpers.attachChild(volume);
        helpers.attachChild(new com.jme3.effect.ParticleEmitter("Particles",
                com.jme3.effect.ParticleMesh.Type.Triangle, 8));
        Node deformOwner = new Node("Morph owner");
        deformOwner.addControl(new com.jme3.anim.MorphControl());
        Geometry attachment = new Geometry("Deform owner attachment", new Box(1, 1, 1));
        deformOwner.attachChild(attachment);
        root.attachChild(deformOwner);
        check(LightmapUvEdits.receivers(root).size() == 2, "Deformable/helper subtree not excluded");
        Mesh originalRigid = rigid.getMesh();
        LightmapUvEdits edit = new LightmapUvEdits(root);
        edit.build(new LightmapUvGenerator.Settings(64, 4, 2), 512, () -> false);
        edit.validate();
        edit.redo();
        check(rigid.getMesh() != originalRigid && rigid.getMesh().getBuffer(Type.TexCoord2) != null
                && dynamic.getMesh().getBuffer(Type.TexCoord2) != null, "Rigid/Dynamic rigid UV2 missing");
        edit.undo();
        check(rigid.getMesh() == originalRigid, "Group undo failed");
        edit.redo();
        LightmapUvEdits clear = new LightmapUvEdits(root);
        clear.build(null, 512, () -> false);
        clear.validate();
        clear.redo();
        check(rigid.getMesh().getBuffer(Type.TexCoord2) == null
                && dynamic.getMesh().getBuffer(Type.TexCoord2) == null
                && rigid.getUserData(LightmapUvGenerator.RESOLUTION_KEY) == null, "Group Clear failed");
        clear.undo();
        check(rigid.getMesh().getBuffer(Type.TexCoord2) != null, "Clear undo failed");
        check(actor.getMesh() == skin && skin.getBuffer(Type.TexCoord2) == oldActorUv
                && skin.getBuffer(Type.BoneWeight) == oldWeights && actor.getControl(ProbeLightmapControl.class) == actorMap
                && morph.getMesh() == morphMesh && attachment.getMesh().getBuffer(Type.TexCoord2) == null,
                "An excluded object was modified");
        Node saved = BinaryExporter.saveAndLoad(new com.jme3.asset.DesktopAssetManager(), root);
        check(((Geometry) saved.getChild("Rigid")).getMesh().getBuffer(Type.TexCoord2) != null, "UV2 not saved");
        try {
            new LightmapUvEdits(actor);
            throw new AssertionError("Deformable-only selection accepted");
        } catch (IllegalArgumentException expected) {
            // No snapshot or edit of excluded data.
        }
        LightmapUvEdits cancelled = new LightmapUvEdits(root);
        try {
            cancelled.build(null, 512, () -> true);
            throw new AssertionError("Cancellation ignored");
        } catch (CancellationException expected) {
            check(rigid.getMesh().getBuffer(Type.TexCoord2) != null, "Cancellation changed source");
        }
        LightmapUvEdits stale = new LightmapUvEdits(root);
        stale.build(null, 512, () -> false);
        rigid.setMesh(new Box(2, 2, 2));
        try {
            stale.validate();
            throw new AssertionError("Stale mesh edit accepted");
        } catch (CancellationException expected) {
            // New source stays intact.
        }
        Mesh crowded = new Mesh();
        float[] positions = new float[100 * 9];
        for (int i = 0; i < 100; i++) {
            int p = i * 9;
            positions[p] = i * 3;
            positions[p + 3] = i * 3 + 1;
            positions[p + 6] = i * 3;
            positions[p + 7] = 1;
        }
        crowded.setBuffer(Type.Position, 3, positions);
        crowded.updateCounts();
        var minimum = new LightmapUvGenerator.Settings(64, 4, 2);
        var layout = LightmapUvGenerator.generateForBake(crowded, minimum, 512);
        check(layout.resolution() > 64 && crowded.getBuffer(Type.TexCoord2) == null, "Capacity retry/source preservation failed");
        var measure = Class.forName("com.jme3.gde.core.assets.LightmapUvPacking")
                .getDeclaredMethod("minimumIslandArea", Mesh.class, int.class);
        measure.setAccessible(true);
        double smallest = (double) measure.invoke(null, layout.mesh(), layout.resolution());
        check(smallest >= 4 || layout.resolution() == 512, "Minimum positive-island density not enforced");
        System.out.println("AUTO_UV_DENSITY size=" + layout.resolution() + " minIslandTexels=" + smallest);
        try {
            LightmapUvGenerator.generateForBake(crowded, minimum, 64);
            throw new AssertionError("Impossible GPU limit accepted");
        } catch (java.io.IOException expected) {
            // No partial mesh installed.
        }
        System.out.println("LIGHTMAP_UV_SUBTREE_PASS (deformables untouched, auto capacity, Clear, undo, persistence)");
    }
}

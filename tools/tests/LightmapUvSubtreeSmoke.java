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
    private static void testInspectorProgress() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                ModelDetailTopComponent panel = new ModelDetailTopComponent();
                Class<?> type = panel.getClass();
                var update = type.getDeclaredMethod("setUvProgress", int.class, int.class, String.class);
                var select = type.getDeclaredMethod("cancelOnSelectionChange", Spatial.class,
                        com.jme3.gde.core.scene.SceneRequest.class);
                update.setAccessible(true);
                select.setAccessible(true);
                var scene = new com.jme3.gde.core.scene.SceneRequest(panel, null);
                var token = new java.util.concurrent.atomic.AtomicBoolean();
                setField(panel, "cancellation", token);
                setField(panel, "uvEditToken", token);
                setField(panel, "uvScene", scene);
                Node original = new Node("Scene Root");
                setField(panel, "selected", original);
                var model = new javax.swing.JPanel();
                var layout = new java.awt.CardLayout();
                var cards = new javax.swing.JPanel(layout);
                cards.add(model, "model");
                cards.add(new javax.swing.JPanel(), "none");
                setField(panel, "embedded", model);
                setField(panel, "contexts", layout);
                setField(panel, "contextual", cards);
                var showContext = type.getDeclaredMethod("showContext", String.class);
                showContext.setAccessible(true);
                update.invoke(panel, 1, 2, "Generating UV2: Rigid");
                select.invoke(panel, new Node("Other node"), scene);
                showContext.invoke(panel, "none");
                var footer = (javax.swing.JPanel) getField(panel, "uvProgressFooter");
                var bar = (javax.swing.JProgressBar) getField(panel, "uvBackgroundProgress");
                check(!token.get() && footer.isVisible() && bar.getString().equals("1 / 2")
                        && bar.getToolTipText().endsWith("Rigid"), "Changing selection lost the UV progress");
                showContext.invoke(panel, "model");
                var inline = (javax.swing.JProgressBar) getField(panel, "progress");
                check(!footer.isVisible() && inline.getValue() == 1, "Returning to the original card lost UV progress");
                select.invoke(panel, original, new com.jme3.gde.core.scene.SceneRequest(panel, null));
                check(token.get(), "Replacing the scene must cancel UV regeneration");
                var minimum = (javax.swing.JSpinner) getField(panel, "uvResolution");
                check((int) minimum.getValue() == 512, "Inspector must default to a 512 pixel minimum");
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        });
    }

    private static Object getField(ModelDetailTopComponent panel, String name) throws ReflectiveOperationException {
        var field = panel.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(panel);
    }

    private static void setField(ModelDetailTopComponent panel, String name, Object value)
            throws ReflectiveOperationException {
        var field = panel.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(panel, value);
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
        var progress = new java.util.ArrayList<LightmapUvEdits.Progress>();
        edit.build(new LightmapUvGenerator.Settings(64, 4, 2), 512, () -> false, progress::add);
        check(progress.equals(java.util.List.of(
                new LightmapUvEdits.Progress(0, 2, "Rigid"),
                new LightmapUvEdits.Progress(1, 2, "Dynamic rigid"),
                new LightmapUvEdits.Progress(2, 2, null))), "Progress must identify each eligible geometry");
        check(rigid.getMesh() == originalRigid, "Progress changed live mesh before commit");
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
        ProbeLightmapControl rigidMap = new ProbeLightmapControl("rigid.j3o", rigid.getWorldTransform(), 100);
        rigid.addControl(rigidMap);
        var modified = new java.util.concurrent.atomic.AtomicBoolean();
        SceneLightmapEdit unlink = new SceneLightmapEdit(root, () -> modified.set(true));
        check(unlink.size() == 2, "Clear Scene Lightmaps missed a receiver");
        unlink.sceneRedo();
        check(modified.get() && actor.getControl(ProbeLightmapControl.class) == null
                && rigid.getControl(ProbeLightmapControl.class) == null, "Clear Scene Lightmaps did not unlink receivers");
        check(rigid.getMesh().getBuffer(Type.TexCoord2) != null && volume.getParent() == helpers,
                "Clear Scene Lightmaps changed UV2 or probes");
        Node savedWithoutLightmaps = BinaryExporter.saveAndLoad(new com.jme3.asset.DesktopAssetManager(), root);
        check(savedWithoutLightmaps.getChild("Rigid").getControl(ProbeLightmapControl.class) == null,
                "Cleared Lightmap binding present after J3O reload");
        unlink.sceneUndo();
        check(rigid.getControl(ProbeLightmapControl.class) == rigidMap
                && actor.getControl(ProbeLightmapControl.class) == actorMap, "Undo did not restore Lightmap bindings");
        unlink.sceneRedo();
        check(rigid.getControl(ProbeLightmapControl.class) == null, "Redo did not clear Lightmap binding");
        testInspectorProgress();
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

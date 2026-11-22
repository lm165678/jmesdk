package com.jme3.gde.core.assets;

import com.jme3.anim.AnimClip;
import com.jme3.anim.AnimComposer;
import com.jme3.anim.AnimTrack;
import com.jme3.anim.Armature;
import com.jme3.anim.Joint;
import com.jme3.anim.SkinningControl;
import com.jme3.anim.TransformTrack;
import com.jme3.anim.graph.AnimationGraph;
import com.jme3.anim.graph.AnimationGraph.Overlay;
import com.jme3.anim.graph.AnimationGraph.Parameter;
import com.jme3.anim.graph.AnimationGraph.State;
import com.jme3.anim.graph.AnimationGraph.Type;
import com.jme3.anim.graph.AnimationGraphPlayer;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

public final class MaskedBlendSmoke {
    public static void main(String[] args) throws Exception {
        Node actor = new Node("Actor");
        Joint hips = new Joint("Hips");
        Joint chest = new Joint("Chest");
        Joint arm = new Joint("Arm");
        Joint leg = new Joint("Leg");
        hips.addChild(chest);
        hips.addChild(leg);
        chest.addChild(arm);
        Armature rig = new Armature(new Joint[]{hips, chest, arm, leg});
        rig.saveBindPose();
        rig.saveInitialPose();
        Node skin = new Node("Skin");
        skin.addControl(new SkinningControl(rig));
        actor.attachChild(skin);
        AnimComposer composer = new AnimComposer();
        actor.addControl(composer);
        composer.addAnimClip(clip("Idle", arm, 0, leg, 0));
        composer.addAnimClip(clip("Right", arm, 2, leg, 4));
        composer.addAnimClip(clip("Forward", arm, 4, leg, 8));
        composer.addAnimClip(clip("Hold", arm, 10, leg, 99));
        List<Parameter> parameters = List.of(new Parameter("X", Type.FLOAT, 0), new Parameter("Y", Type.FLOAT, 0),
                new Parameter("Armed", Type.BOOLEAN, 0));
        var state = new State("Move", "X", "Y", List.of("Idle", "Right", "Forward"),
                new float[]{0, 1, 0}, new float[]{0, 0, 1}, true, 0, 0);
        var graph = new AnimationGraph("Move", parameters, List.of(state), List.of(),
                new Overlay("Armed", "Hold", 0.5f, List.of(AnimationGraph.jointPath(chest))));
        actor.setUserData(AnimationGraph.USER_DATA, graph.encode());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new BinaryExporter().save(actor, bytes);
        Node restored = (Node) new BinaryImporter().load(new ByteArrayInputStream(bytes.toByteArray()));
        Armature loaded = restored.getChild("Skin").getControl(SkinningControl.class).getArmature();
        try (var player = AnimationGraphPlayer.attach(restored)) {
            check(loaded, 0, 0);
            player.setFloat("X", 0.5f);
            player.setFloat("Y", 0.5f);
            restored.updateLogicalState(0.1f);
            check(loaded, 2, 4);
            player.setBoolean("Armed", true);
            restored.updateLogicalState(0.25f);
            check(loaded, 6, 4);
            restored.updateLogicalState(0.25f);
            check(loaded, 10, 4);
            player.setFloat("X", 0);
            player.setFloat("Y", 1);
            restored.updateLogicalState(0f);
            check(loaded, 10, 8);
            player.setBoolean("Armed", false);
            restored.updateLogicalState(0.25f);
            check(loaded, 7, 8);
            restored.updateLogicalState(0.25f);
            check(loaded, 4, 8);
            player.restart();
            check(loaded, 0, 0);
        }
        var legacy = new AnimationGraph("Idle", List.of(), List.of(
                new State("Idle", "", List.of("Idle"), new float[]{0}, true, 0, 0)), List.of());
        var decoded = AnimationGraph.decode(legacy.encode());
        if (decoded.overlay != null || decoded.states.get(0).isTwoDimensional()) {
            throw new AssertionError("Legacy defaults changed");
        }
        System.out.println("MASKED_2D_BLEND_PASS");
    }

    private static AnimClip clip(String name, Joint arm, float pose, Joint leg, float stride) {
        AnimClip clip = new AnimClip(name);
        clip.setTracks(new AnimTrack<?>[]{track(arm, pose), track(leg, stride)});
        return clip;
    }

    private static TransformTrack track(Joint joint, float position) {
        var value = new Vector3f(position, 0, 0);
        return new TransformTrack(joint, new float[]{0, 1}, new Vector3f[]{value, value.clone()}, null, null);
    }

    private static void check(Armature rig, float arm, float leg) {
        float actualArm = rig.getJoint("Arm").getLocalTranslation().x;
        float actualLeg = rig.getJoint("Leg").getLocalTranslation().x;
        if (Math.abs(actualArm - arm) > 0.0001f || Math.abs(actualLeg - leg) > 0.0001f) {
            throw new AssertionError("Expected " + arm + "/" + leg + ", got " + actualArm + "/" + actualLeg);
        }
    }
}

package com.jme3.gde.core.assets;

import com.jme3.anim.AnimClip;
import com.jme3.anim.AnimComposer;
import com.jme3.anim.AnimTrack;
import com.jme3.anim.TransformTrack;
import com.jme3.anim.graph.AnimationGraph;
import com.jme3.anim.graph.AnimationGraph.Operator;
import com.jme3.anim.graph.AnimationGraph.Parameter;
import com.jme3.anim.graph.AnimationGraph.RootMotion;
import com.jme3.anim.graph.AnimationGraph.State;
import com.jme3.anim.graph.AnimationGraph.Transition;
import com.jme3.anim.graph.AnimationGraph.Type;
import com.jme3.anim.graph.AnimationGraphPlayer;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import java.io.ByteArrayOutputStream;
import java.util.List;

/** Runs gameplay root-motion checks after binary serialization with no SDK runtime. */
public final class RootMotionSmoke {
    public static void main(String[] args) throws Exception {
        Node actor = roundTrip(graph(true, false));
        Node scene = new Node("Game");
        scene.attachChild(actor);
        scene.updateLogicalState(0.75f);
        scene.updateLogicalState(0.5f);
        close(2.5f, actor.getLocalTranslation().z, "automatic loop movement");
        close(0f, actor.getChild("Motion").getLocalTranslation().z, "no double movement");
        close(0.125f, actor.getChild("Motion").getLocalTranslation().y, "vertical pose retained");
        var player = (AnimationGraphPlayer) actor.getControl(AnimComposer.class).getCurrentAction();
        player.setFloat("Speed", 1.5f);
        scene.updateLogicalState(0.25f);
        close(3.25f, actor.getLocalTranslation().z, "1D blended movement");
        player.setPaused(true);
        scene.updateLogicalState(2f);
        close(3.25f, actor.getLocalTranslation().z, "pause");
        close(0f, player.getRootMotionDelta().getTranslation().z, "paused delta");
        player.setPaused(false);
        player.restart();
        scene.updateLogicalState(0.25f);
        close(3.75f, actor.getLocalTranslation().z, "restart anchor without jump");
        actor.getControl(AnimComposer.class).setTime(12);
        scene.updateLogicalState(0.25f);
        close(4.25f, actor.getLocalTranslation().z, "seeking the layer clock did not add elapsed displacement");
        AnimComposer mainComposer = actor.getControl(AnimComposer.class);
        mainComposer.setTime(0);
        mainComposer.setGlobalSpeed(-1);
        scene.updateLogicalState(0.25f);
        close(4.25f, actor.getLocalTranslation().z, "unsupported reverse speed cannot wrap and move the actor");
        mainComposer.setGlobalSpeed(1);
        scene.updateLogicalState(0.25f);
        close(4.75f, actor.getLocalTranslation().z, "forward playback resumes after reverse-speed clamp");

        Node extracting = roundTrip(graph(false, true));
        extracting.updateLogicalState(0.5f);
        var manual = AnimationGraphPlayer.attach(extracting);
        close(0f, extracting.getLocalTranslation().z, "extract does not move actor");
        close(1f, manual.getRootMotionDelta().getTranslation().z, "2D mixed delta");
        Node physicsProxy = new Node("Physics proxy");
        manual.setRootMotionConsumer(delta -> {
            float next = physicsProxy.getLocalTranslation().z + delta.getTranslation().z;
            physicsProxy.setLocalTranslation(0, 0, Math.min(0.6f, next));
        });
        extracting.updateLogicalState(0.5f);
        close(0.6f, physicsProxy.getLocalTranslation().z, "consumer owns position and collision");
        close(0f, extracting.getLocalTranslation().z, "writer remains detached");

        var gate = new Parameter("Change", Type.BOOLEAN, 0);
        var edge = new Transition("Walk", "Run", "Change", Operator.TRUE, 0, 1, -1);
        var transition = new AnimationGraph("Walk", List.of(gate), List.of(single("Walk"), single("Run")),
                List.of(edge), null, new RootMotion("Motion", false, true));
        Node blending = roundTrip(transition);
        blending.updateLogicalState(0.25f);
        AnimationGraphPlayer.attach(blending).setBoolean("Change", true);
        blending.updateLogicalState(0.25f);
        close(1f, blending.getLocalTranslation().z, "transition entry from previous clip");
        blending.updateLogicalState(0.5f);
        blending.updateLogicalState(0.5f);
        close(4f, blending.getLocalTranslation().z, "crossfade motion");

        var turnGraph = new AnimationGraph("Turn", List.of(), List.of(single("Turn")), List.of(),
                null, new RootMotion("Motion", false, true));
        Node turning = roundTrip(turnGraph);
        turning.updateLogicalState(1f);
        close(1f, turning.getLocalTranslation().x, "turn first translation");
        turning.updateLogicalState(1f);
        close(-1f, turning.getLocalTranslation().z, "turn composes second loop in new orientation");
        close(0f, turning.getChild("Motion").getLocalRotation().getY(), "yaw removed from pose");
        Node rotatedRig = skeletalRoundTrip();
        for (int frame = 0; frame < 120; ++frame) {
            rotatedRig.updateLogicalState(1f / 60);
        }
        close(2f, rotatedRig.getLocalTranslation().x, "skeletal motion honors parent rotation and scale");

        Node legacy = roundTrip(new AnimationGraph("Walk", List.of(), List.of(single("Walk")), List.of()));
        legacy.updateLogicalState(1f);
        if (legacy.getControl(AnimComposer.class).getCurrentAction() != null) {
            throw new AssertionError("Legacy graph auto-started without opt-in");
        }
        verifyLinkedAndEmbedded();
        System.out.println("ROOT_MOTION_J3O_PASS");
    }

    private static void verifyLinkedAndEmbedded() throws Exception {
        var assets = java.nio.file.Files.createTempDirectory("root-motion-links-");
        var isolated = java.nio.file.Files.createTempDirectory("root-motion-embedded-");
        var manager = new com.jme3.asset.DesktopAssetManager(true);
        manager.registerLocator(assets.toString(), com.jme3.asset.plugins.FileLocator.class);
        var exporter = new BinaryExporter();
        Node library = roundTrip(new AnimationGraph("Walk", List.of(), List.of(single("Walk")), List.of()));
        library.setUserData(AnimationGraph.USER_DATA, null);
        exporter.save(library, assets.resolve("Library.j3o").toFile());
        String name = com.jme3.export.binary.AnimationLinkResolver.clipIds(manager, "Library.j3o").stream()
                .filter(id -> id.endsWith("#Walk")).findFirst().orElseThrow();
        Node owner = new Node("Actor");
        owner.attachChild(new Node("Motion"));
        owner.addControl(new AnimComposer());
        com.jme3.export.binary.AnimationLinkResolver.writeLinks(owner, List.of(
                new com.jme3.export.binary.AnimationLinkResolver.Link("Library.j3o", name)));
        var state = new State("Walk", "", List.of(name), new float[]{0}, true, 0, 0);
        var graph = new AnimationGraph("Walk", List.of(), List.of(state), List.of(),
                null, new RootMotion("Motion", false, true));
        owner.setUserData(AnimationGraph.USER_DATA, graph.encode());
        exporter.save(owner, assets.resolve("Actor.j3o").toFile());
        Node linked = (Node) manager.loadModel("Actor.j3o");
        linked.updateLogicalState(0.5f);
        close(1f, linked.getLocalTranslation().z, "linked AssetManager load auto-applies motion");
        Node copy = (Node) manager.loadModel("Actor.j3o");
        com.jme3.export.binary.AnimationLinkResolver.embed(manager, "Actor.j3o", copy);
        exporter.save(copy, isolated.resolve("Actor.j3o").toFile());
        var runtime = new com.jme3.asset.DesktopAssetManager(true);
        runtime.registerLocator(isolated.toString(), com.jme3.asset.plugins.FileLocator.class);
        Node embedded = (Node) runtime.loadModel("Actor.j3o");
        embedded.updateLogicalState(0.5f);
        close(1f, embedded.getLocalTranslation().z, "embedded J3O auto-applies without source library");
    }

    private static AnimationGraph graph(boolean apply, boolean cartesian) {
        var speed = new Parameter("Speed", Type.FLOAT, cartesian ? 0.5f : 1f);
        var state = cartesian
                ? new State("Move", "Speed", "Side", List.of("Idle", "Walk", "Run"),
                        new float[]{0, 1, 0}, new float[]{0, 0, 1}, true, 0, 0)
                : new State("Move", "Speed", List.of("Idle", "Walk", "Run"),
                        new float[]{0, 1, 2}, true, 0, 0);
        var parameters = cartesian ? List.of(speed, new Parameter("Side", Type.FLOAT, 0.5f)) : List.of(speed);
        return new AnimationGraph("Move", parameters, List.of(state), List.of(),
                null, new RootMotion("Motion", false, apply));
    }

    private static State single(String clip) {
        return new State(clip, "", List.of(clip), new float[]{0}, true, 0, 0);
    }

    private static Node roundTrip(AnimationGraph graph) throws Exception {
        Node actor = new Node("Actor");
        Node track = new Node("Motion");
        actor.attachChild(track);
        AnimComposer composer = new AnimComposer();
        actor.addControl(composer);
        composer.addAnimClip(clip("Idle", track, 0, 0, 0));
        composer.addAnimClip(clip("Walk", track, 0, 2, 0));
        composer.addAnimClip(clip("Run", track, 0, 4, 0));
        composer.addAnimClip(clip("Turn", track, 1, 0, (float) (Math.PI / 2)));
        actor.setUserData(AnimationGraph.USER_DATA, graph.encode());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new BinaryExporter().save(actor, bytes);
        return (Node) new BinaryImporter().load(bytes.toByteArray());
    }

    private static AnimClip clip(String name, Node track, float x, float z, float yaw) {
        var animation = new AnimClip(name);
        var start = new Vector3f();
        var end = new Vector3f(x, 0.5f, z);
        var endRotation = new Quaternion().fromAngleNormalAxis(yaw, Vector3f.UNIT_Y);
        animation.setTracks(new AnimTrack<?>[]{new TransformTrack(track, new float[]{0, 1},
                new Vector3f[]{start, end}, new Quaternion[]{new Quaternion(), endRotation}, null)});
        return animation;
    }

    private static Node skeletalRoundTrip() throws Exception {
        Node actor = new Node("Actor");
        Node skin = new Node("Skin");
        actor.attachChild(skin);
        skin.setLocalScale(0.5f);
        skin.setLocalRotation(new Quaternion().fromAngleNormalAxis((float) (Math.PI / 2), Vector3f.UNIT_Y));
        var joint = new com.jme3.anim.Joint("Root");
        var rig = new com.jme3.anim.Armature(new com.jme3.anim.Joint[]{joint});
        rig.saveBindPose();
        rig.saveInitialPose();
        skin.addControl(new com.jme3.anim.SkinningControl(rig));
        var clip = new AnimClip("Walk");
        clip.setTracks(new AnimTrack<?>[]{new TransformTrack(joint, new float[]{0, 1},
                new Vector3f[]{new Vector3f(), new Vector3f(0, 0, 2)}, null, null)});
        var composer = new AnimComposer();
        composer.addAnimClip(clip);
        actor.addControl(composer);
        var graph = new AnimationGraph("Walk", List.of(), List.of(single("Walk")), List.of(),
                null, new RootMotion("Root", true, true));
        actor.setUserData(AnimationGraph.USER_DATA, graph.encode());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new BinaryExporter().save(actor, bytes);
        return (Node) new BinaryImporter().load(bytes.toByteArray());
    }

    private static void close(float expected, float actual, String message) {
        if (Math.abs(expected - actual) > 0.0002f || !Float.isFinite(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}

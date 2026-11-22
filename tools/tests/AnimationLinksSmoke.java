package com.jme3.gde.core.assets;

import com.jme3.anim.AnimClip;
import com.jme3.anim.AnimComposer;
import com.jme3.anim.AnimTrack;
import com.jme3.anim.Armature;
import com.jme3.anim.Joint;
import com.jme3.anim.SkinningControl;
import com.jme3.anim.TransformTrack;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.export.binary.AnimationLinkResolver;
import com.jme3.export.binary.AnimationLinkResolver.Link;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Checks asset-only animation sources and split composer/skin layouts after a real J3O round trip. */
public final class AnimationLinksSmoke {
    public static void main(String[] args) throws Exception {
        Path assets = Files.createTempDirectory(Path.of(args[0]), "animation-links-");
        var manager = manager(assets);
        Node a = actor();
        Node b = motion("Idle", 1);
        save(assets, "C.j3o", motion("Walk", 2));
        save(assets, "D.j3o", motion("Run", 3));
        AnimationLinkResolver.writeLinks(b, List.of(new Link("C.j3o", null), new Link("D.j3o", null)));
        save(assets, "B.j3o", b);
        Link bLibrary = new Link("B.j3o", null);
        Link cLibrary = new Link("C.j3o", null);
        List<Link> links = List.of(bLibrary, cLibrary);
        List<AnimClip> bound = AnimationLinkResolver.validate(manager, "A.j3o", a, links);
        require(bound.size() == 3, "Transitive clip did not deduplicate");
        expectFailure("already exists", () -> AnimationLinkResolver.validate(
                manager, "A.j3o", a, List.of(bLibrary, bLibrary)));
        String walk = AnimationLinkResolver.clipIds(manager, "B.j3o").stream()
                .filter(name -> name.endsWith("#Walk")).findFirst().orElseThrow();
        require(AnimationLinkResolver.validate(manager, "A.j3o", a, List.of(new Link("B.j3o", walk))).size() == 1,
                "Selecting a clip imported the whole library");

        AnimationLinkResolver.writeLinks(a, links);
        save(assets, "A.j3o", a);
        Node restored = (Node) manager.loadModel("A.j3o");
        AnimComposer composer = restored.getControl(AnimComposer.class);
        require(restored.getControl(SkinningControl.class) == null, "Composer was moved onto the rig");
        require(composer.getEmbeddedAnimClips().isEmpty() && composer.getAnimClips().size() == 3,
                "J3O did not preserve references as references");
        require(drives(restored, walk) == 1, "Resolved animation did not drive the child rig");
        Node another = (Node) manager.loadModel("A.j3o");
        require(joint(another).getLocalTranslation().x == 0, "An instance inherited another instance's pose");
        require(drives(another, walk) == 1, "Cloned tracks did not target the cloned rig");

        expectFailure("B.j3o → A.j3o → B.j3o", () -> AnimationLinkResolver.validate(
                manager, "B.j3o", b, List.of(new Link("A.j3o", null))));
        String idle = AnimationLinkResolver.clipIds(manager, "B.j3o").stream()
                .filter(name -> name.endsWith("#Idle")).findFirst().orElseThrow();
        AnimationLinkResolver.writeLinks(b, List.of(new Link("A.j3o", null),
                new Link("C.j3o", null), new Link("D.j3o", null)));
        save(assets, "B.j3o", b);
        expectFailure("A.j3o → B.j3o → A.j3o", () -> AnimationLinkResolver.validate(
                manager, "A.j3o", a, List.of(new Link("B.j3o", idle))));
        manager.clearCache();
        Node recovered = (Node) manager.loadModel("A.j3o");
        require(recovered.getControl(AnimComposer.class).getAnimClips().size() == 3,
                "A cyclic branch hid compatible clips");

        AnimationLinkResolver.writeLinks(b, List.of(cLibrary, new Link("D.j3o", null)));
        save(assets, "B.j3o", b);
        save(assets, "C.j3o", motion("Walk", 4));
        manager.clearCache();
        Node current = (Node) manager.loadModel("A.j3o");
        Node copy = current.clone(true);
        AnimationLinkResolver.embed(manager, "A.j3o", copy);
        require(AnimationLinkResolver.links(copy).isEmpty() && AnimationLinkResolver.links(current).size() == 2,
                "Embedding removed the original links");
        Path isolated = Files.createTempDirectory(assets.getParent(), "animation-export-");
        save(isolated, "Actor.j3o", copy);
        Node exported = (Node) manager(isolated).loadModel("Actor.j3o");
        require(exported.getControl(AnimComposer.class).getEmbeddedAnimClips().size() == 3,
                "Export did not embed all clips");
        require(drives(exported, walk) == 2, "Export did not include updated motion or rebound tracks");
        if (args.length > 1) {
            verifyUfbx(assets, Path.of(args[1]));
        }
        System.out.println("ANIMATION_LINKS_SMOKE_PASS");
    }

    private static void verifyUfbx(Path assets, Path fixtures) throws Exception {
        DesktopAssetManager manager = manager(assets);
        var loader = Class.forName("com.jme3.gde.autodesk.UfbxLoader").asSubclass(com.jme3.asset.AssetLoader.class);
        manager.registerLoader(loader, "fbx");
        String prefix = "maya_game_sausage_6100_ascii";
        Files.copy(fixtures.resolve(prefix + ".fbx"), assets.resolve("actor.fbx"));
        Files.copy(fixtures.resolve(prefix + "_wiggle.fbx"), assets.resolve("wiggle.fbx"));
        Files.copy(fixtures.resolve(prefix + "_spin.fbx"), assets.resolve("spin.fbx"));
        Node actor = (Node) manager.loadModel("actor.fbx");
        require(actor.getControl(SkinningControl.class) == null
                && actor.descendantMatches(Node.class).stream().anyMatch(node -> node.getControl(SkinningControl.class) != null),
                "Expected ufbx to place skinning under a child node");
        save(assets, "wiggle.j3o", manager.loadModel("wiggle.fbx"));
        save(assets, "spin.j3o", manager.loadModel("spin.fbx"));
        List<Link> refs = List.of(new Link("wiggle.j3o", null), new Link("spin.j3o", null));
        var clips = AnimationLinkResolver.validate(manager, "UfbxActor.j3o", actor, refs);
        require(clips.size() >= 2, "Independent ufbx motion files did not both bind");
        AnimationLinkResolver.writeLinks(actor, refs);
        save(assets, "UfbxActor.j3o", actor);
        Node restored = (Node) manager.loadModel("UfbxActor.j3o");
        Node embedded = restored.clone(true);
        AnimationLinkResolver.embed(manager, "UfbxActor.j3o", embedded);
        Path isolated = Files.createTempDirectory(assets.getParent(), "ufbx-animation-export-");
        save(isolated, "OnlyActor.j3o", embedded);
        Node exported = (Node) manager(isolated).loadModel("OnlyActor.j3o");
        AnimComposer composer = exported.getControl(AnimComposer.class);
        require(composer.getEmbeddedAnimClips().size() >= 2 && AnimationLinkResolver.links(exported).isEmpty(),
                "Standalone ufbx export did not retain both clips");
        var rig = exported.descendantMatches(Node.class).stream()
                .map(node -> node.getControl(SkinningControl.class)).filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow().getArmature();
        for (AnimClip clip : clips) {
            composer.setCurrentAction(clip.getName());
            exported.updateLogicalState(0f);
            var before = java.util.Arrays.stream(rig.computeSkinningMatrices())
                    .map(matrix -> matrix.clone()).toArray(com.jme3.math.Matrix4f[]::new);
            boolean moved = false;
            for (int i = 0; i < 8 && !moved; ++i) {
                exported.updateLogicalState((float) clip.getLength() / 9);
                moved = !java.util.Arrays.equals(before, rig.computeSkinningMatrices());
            }
            require(moved, "Exported ufbx clip did not animate the skin: " + clip.getName());
        }
        System.out.println("UFBX_EXTERNAL_ANIMATION_PASS");
    }

    private static Node actor() {
        Node root = new Node("Actor");
        Node mesh = new Node("MeshWithSkin");
        root.attachChild(mesh);
        Joint rig = new Joint("Rig");
        Joint hip = new Joint("Hip");
        rig.addChild(hip);
        Armature armature = new Armature(new Joint[]{rig, hip});
        armature.saveBindPose();
        armature.saveInitialPose();
        mesh.addControl(new SkinningControl(armature));
        root.addControl(new AnimComposer());
        return root;
    }

    private static Node motion(String name, float amount) {
        Node root = new Node("Motion");
        Node rig = new Node("Rig");
        Node hip = new Node("Hip");
        root.attachChild(rig);
        rig.attachChild(hip);
        AnimClip clip = new AnimClip(name);
        clip.setTracks(new AnimTrack<?>[]{new TransformTrack(hip, new float[]{0, 1},
                new Vector3f[]{new Vector3f(), new Vector3f(amount, 0, 0)}, null, null)});
        AnimComposer composer = new AnimComposer();
        composer.addAnimClip(clip);
        root.addControl(composer);
        return root;
    }

    private static float drives(Node model, String name) {
        model.getControl(AnimComposer.class).setCurrentAction(name);
        model.updateLogicalState(0.5f);
        model.updateLogicalState(0f);
        return joint(model).getLocalTranslation().x;
    }

    private static Joint joint(Node model) {
        return model.getChild("MeshWithSkin").getControl(SkinningControl.class).getArmature().getJoint("Hip");
    }

    private static DesktopAssetManager manager(Path path) {
        var manager = new DesktopAssetManager(true);
        manager.registerLocator(path.toString(), FileLocator.class);
        return manager;
    }

    private static void save(Path path, String name, Spatial model) throws IOException {
        new BinaryExporter().save(model, path.resolve(name).toFile());
    }

    private static void expectFailure(String message, Checked work) throws Exception {
        try {
            work.run();
            throw new AssertionError("Expected failure: " + message);
        } catch (IOException exception) {
            require(exception.getMessage().contains(message), exception.getMessage());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private interface Checked {
        void run() throws Exception;
    }
}

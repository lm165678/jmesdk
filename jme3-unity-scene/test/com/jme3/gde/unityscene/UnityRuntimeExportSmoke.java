package com.jme3.gde.unityscene;

import com.jme3.app.LegacyApplication;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.gde.core.assets.ProjectAssetManager;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.unity.runtime.ImportedBehaviour;
import com.jme3.unity.runtime.UnityBehaviourState;
import com.jme3.unity.runtime.UnityPrefabInstance;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.tools.ToolProvider;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/** Uses a scratch copy of actual Unity assets and a minimal lifecycle regression scene. */
public final class UnityRuntimeExportSmoke {
    private static final String SCRIPT = "b89f78bad05743ac967a055bb628206a";
    private static final String POINT = "7bf4998eb8e07e44b95bd1544ae332f4";

    public static void main(String[] args) throws Exception {
        try {
            verify(Path.of(args[0]).toAbsolutePath());
            System.out.println("UNITY_RUNTIME_EXPORT_SMOKE_PASS");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void verify(Path workspace) throws Exception {
        Path assets = workspace.resolve("assets");
        Path sources = Files.createTempDirectory(workspace, "java-");
        FileObject root = org.netbeans.modules.masterfs.filebasedfs.FileBasedFileSystem.getFileObject(
                FileUtil.normalizeFile(assets.toFile()));
        ProjectAssetManager manager = new ProjectAssetManager(root);
        FileObject actualScene = root.getFileObject("building-a-graph/Scenes/TestScene1.unity");
        FileObject actualPrefab = root.getFileObject("building-a-graph/Prefabs/Point.prefab");
        Path sceneJ3o = UnityProjectExport.write(actualScene, manager, sources, "mygame.controls");
        Path pointJ3o = UnityProjectExport.write(actualPrefab, manager, sources, "mygame.controls");
        Node loadedScene = load(root, assets, sceneJ3o);
        Node point = load(root, assets, pointJ3o);
        check(loadedScene.getQuantity() > 0, "Real Unity scene is empty");
        var renderables = new ArrayList<Geometry>();
        point.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry) {
                renderables.add((Geometry) spatial);
            }
        });
        check(!renderables.isEmpty() && renderables.get(0).getMaterial() != null,
                "Real prefab mesh or material missing");
        Node block = load(root, assets, UnityProjectExport.write(root.getFileObject("Block.prefab"),
                manager, sources, "mygame.controls"));
        int[] components = {0};
        block.depthFirstTraversal(spatial -> {
            for (int i = 0; i < spatial.getNumControls(); i++) {
                if (spatial.getControl(i) instanceof ImportedBehaviour) {
                    components[0]++;
                }
            }
        });
        check(components[0] > 0, "Real prefab lost serialized script bindings");
        try {
            UnityProjectExport.write(root.getFileObject("RockLarge_A_mdl.prefab"), manager,
                    sources, "mygame.controls");
            throw new AssertionError("Binary Unity prefab should produce a diagnostic");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().toLowerCase(java.util.Locale.ROOT).contains("binary"),
                    "Unexpected binary prefab diagnostic: " + expected);
        }
        System.out.println("UNITY_ACTUAL_ASSETS_PASS scene=" + loadedScene.getQuantity()
                + " pointGeometry=" + renderables.size() + " blockBehaviours=" + components[0]);

        long transformId = UnityYaml.parse(actualPrefab.asText("UTF-8")).stream()
                .filter(doc -> doc.classId() == 4).findFirst().orElseThrow().fileId();
        String header = "%YAML 1.1\n%TAG !u! tag:unity3d.com,2011:\n";
        Path consumer = Files.createTempFile(assets, "Wrapper-", ".unity");
        Files.writeString(consumer, header + prefabInstance(90, POINT, transformId, 3, 0)
                + prefabInstance(91, POINT, transformId, 8, 0));
        root.refresh();
        Path linkedFile = UnityProjectExport.write(root.getFileObject(consumer.getFileName().toString()),
                manager, sources, "mygame.controls");
        Node linked = load(root, assets, linkedFile);
        check(linked.getQuantity() == 2 && linked.getChild(0) instanceof UnityPrefabInstance,
                "Actual Point prefab must use a linked instance");
        Node first = (Node) ((Node) ((Node) linked.getChild(0)).getChild(0)).getChild(0);
        Node second = (Node) ((Node) ((Node) linked.getChild(1)).getChild(0)).getChild(0);
        check(first != second && first.getLocalTranslation().x == 3
                && second.getLocalTranslation().x == 8, "Prefab transform override isolation");
        Path template = linkedFile.getParent().resolve("Prefab-" + POINT + ".j3o");
        Node updated = load(root, assets, template);
        updated.getChild(0).setLocalTranslation(99, 7, 0);
        new BinaryExporter().save(updated, template.toFile());
        linked = load(root, assets, linkedFile);
        first = (Node) ((Node) ((Node) linked.getChild(0)).getChild(0)).getChild(0);
        second = (Node) ((Node) ((Node) linked.getChild(1)).getChild(0)).getChild(0);
        check(first.getLocalTranslation().x == 3 && second.getLocalTranslation().x == 8
                && first.getLocalTranslation().y == 7 && second.getLocalTranslation().y == 7,
                "Reopened scene must inherit updated prefab properties and preserve overrides");

        Path fixture = Files.createTempDirectory(assets, "lifecycle-");
        Files.writeString(fixture.resolve("Door.cs"), """
                using UnityEngine;
                public class Door : MonoBehaviour {
                    [SerializeField] private float speed = 1f;
                    public Transform panel;
                    public bool opened;
                    void Awake() { opened = true; }
                    void Start() { panel.Translate(1, 0, 0); }
                    void Update() { if (opened && panel != null) { panel.Translate(speed * Time.deltaTime, 0, 0); } }
                    void LateUpdate() { panel.Translate(0, 1, 0); }
                    void FixedUpdate() { panel.Translate(0, 0, 1); }
                    void OnDisable() { panel.Translate(0, 2, 0); }
                    void OnDestroy() { panel.Translate(0, 4, 0); }
                }
                """);
        String doorGuid = "54e0b6109c8f4f6890dfcc94cffaacfc";
        Files.writeString(fixture.resolve("Door.cs.meta"), "guid: " + SCRIPT + "\n");
        Files.writeString(fixture.resolve("Door.prefab.meta"), "guid: " + doorGuid + "\n");
        Files.writeString(fixture.resolve("Door.prefab"), header + gameObject(1, 4, "Door", 0)
                + gameObject(2, 5, "Panel", 4) + """
                --- !u!114 &114
                MonoBehaviour:
                  m_GameObject: {fileID: 1}
                  m_Script: {fileID: 11500000, guid: %s, type: 3}
                  m_Enabled: 1
                  panel: {fileID: 5}
                """.formatted(SCRIPT));
        Files.writeString(fixture.resolve("Doors.unity"), header
                + prefabInstance(101, doorGuid, 4, 2, 2)
                + prefabInstance(102, doorGuid, 4, 4, 5));
        root.refresh();
        Path gameJ3o = UnityProjectExport.write(root.getFileObject(fixture.getFileName() + "/Doors.unity"),
                manager, sources, "mygame.controls");
        Node game = load(root, assets, gameJ3o);
        Node doorA = (Node) game.getChild("Door");
        Node a = panel(doorA);
        Node b = panel((Node) game.getChild(1));
        game.updateLogicalState(1);
        check(a.getLocalTranslation().x == 0 && b.getLocalTranslation().x == 0,
                "J3O open/preview must be passive");
        Path generated = sources.resolve("mygame/controls/DoorControl_" + SCRIPT + ".java");
        Path binaries = Files.createTempDirectory(workspace, "compiled-");
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", System.getProperty("java.class.path"), "-d", binaries.toString(),
                generated.toString()) == 0, "Generated control did not compile");
        try (var loader = new URLClassLoader(new URL[]{binaries.toUri().toURL()},
                UnityRuntimeExportSmoke.class.getClassLoader())) {
            var app = new LegacyApplication();
            var scheduler = new UnityBehaviourState(game, loader);
            app.getStateManager().attach(scheduler);
            app.getStateManager().update(1);
            check(a.getLocalTranslation().x == 3 && b.getLocalTranslation().x == 6
                    && a.getLocalTranslation().y == 1, "Behaviour fields/reference/Update/LateUpdate mismatch");
            scheduler.fixedUpdate(0.02f);
            check(a.getLocalTranslation().z == -1, "Fixed tick did not preserve Unity Z convention");
            doorA.removeFromParent();
            app.getStateManager().update(1);
            check(a.getLocalTranslation().y == 3, "Temporary detach must disable the behaviour");
            game.attachChild(doorA);
            app.getStateManager().update(1);
            check(a.getLocalTranslation().x == 5, "Reattachment repeated Start");
            scheduler.destroy(doorA);
            check(a.getLocalTranslation().y == 10, "Disable/Destroy dispatch order");
        }
        Files.writeString(generated, "// user edit\n", java.nio.file.StandardOpenOption.APPEND);
        String edited = Files.readString(generated);
        UnityProjectExport.write(root.getFileObject(fixture.getFileName() + "/Doors.unity"),
                manager, sources, "mygame.controls");
        check(edited.equals(Files.readString(generated)), "Reimport overwrote a hand-edited control");
    }

    private static Node load(FileObject root, Path assets, Path j3o) {
        root.refresh();
        return (Node) new ProjectAssetManager(root)
                .loadModel(assets.relativize(j3o).toString().replace('\\', '/'));
    }

    private static Node panel(Node instance) {
        return (Node) ((Node) ((Node) instance.getChild(0)).getChild(0)).getChild("Panel");
    }

    private static String gameObject(int id, int transform, String name, int parent) {
        return """
                --- !u!1 &%d
                GameObject:
                  m_Name: %s
                  m_IsActive: 1
                --- !u!4 &%d
                Transform:
                  m_GameObject: {fileID: %d}
                  m_Father: {fileID: %d}
                """.formatted(id, name, transform, id, parent);
    }

    private static String prefabInstance(int id, String guid, long transform, int x, int speed) {
        return """
                --- !u!1001 &%d
                PrefabInstance:
                  m_SourcePrefab: {fileID: 100100000, guid: %s, type: 3}
                  m_Modification:
                    m_TransformParent: {fileID: 0}
                    m_Modifications:
                    - target: {fileID: %d, guid: %s, type: 3}
                      propertyPath: m_LocalPosition.x
                      value: %d
                      objectReference: {fileID: 0}
                """.formatted(id, guid, transform, guid, x) + (speed == 0 ? "" : """
                    - target: {fileID: 114, guid: %s, type: 3}
                      propertyPath: speed
                      value: %d
                      objectReference: {fileID: 0}
                """.formatted(guid, speed));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

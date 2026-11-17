package com.jme3.gde.unityscene;

import com.jme3.export.binary.BinaryExporter;
import com.jme3.gde.core.assets.ProjectAssetManager;
import com.jme3.material.MatParam;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * Headless parser/builder/J3O smoke test; no OpenGL or SDK UI is required.
 * <pre>
 * 第一步：在临时资产目录生成 Unity 场景、材质、Prefab 和 .meta。
 * 第二步：用真实 UnitySceneLoader 构建场景，不 mock YAML 或 Material。
 * 第三步：断言坐标、网格、材质、Prefab 覆盖和光源，再用 BinaryExporter 保存 J3O。
 * 第四步：通过 AssetManager 重载 J3O，验证场景仍可读取。
 * 运行前提：JDK 与 SDK runtime classpath 已准备好；控制台最后输出
 * UNITY_SCENE_IMPORTER_SMOKE_PASS。该测试不覆盖真机材质渲染效果。
 * </pre>
 */
public final class UnitySceneImporterSmoke {
    private static final String MATERIAL_GUID = "0123456789abcdef0123456789abcdef";
    private static final String PREFAB_GUID = "fedcba9876543210fedcba9876543210";
    private static final String GLASS_GUID = "11111111111111111111111111111111";

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("jme-unity-scene-test");
        try {
            Files.createDirectories(directory.resolve("Materials"));
            Files.createDirectories(directory.resolve("Prefabs"));
            write(directory, "Materials/Green.mat", material());
            write(directory, "Materials/Green.mat.meta", "fileFormatVersion: 2\nguid: "
                    + MATERIAL_GUID + "\nNativeFormatImporter:\n  mainObjectFileID: 2100000\n");
            write(directory, "Materials/Glass.mat", glassMaterial());
            write(directory, "Materials/Glass.mat.meta", "fileFormatVersion: 2\nguid: "
                    + GLASS_GUID + "\nNativeFormatImporter:\n  mainObjectFileID: 2100000\n");
            write(directory, "Prefabs/Cube.prefab", prefab());
            write(directory, "Prefabs/Cube.prefab.meta", "fileFormatVersion: 2\nguid: "
                    + PREFAB_GUID + "\nPrefabImporter:\n  assetBundleName:\n");
            write(directory, "Scene.unity", scene());
            FileObject root = FileUtil.toFileObject(FileUtil.normalizeFile(directory.toFile()));
            root.refresh();
            ProjectAssetManager manager = new ProjectAssetManager(root);
            Node converted = new UnitySceneLoader().load(root.getFileObject("Scene.unity"), manager);

            Node parent = requireNode(converted.getChild("Parent"), "Parent missing");
            requireVector(parent.getLocalTranslation(), new Vector3f(1, 2, -3),
                    "Unity position conversion");
            Node cube = requireNode(parent.getChild("Cube"), "Cube missing");
            Geometry geometry = (Geometry) cube.getChild(0);
            MatParam baseColor = geometry.getMaterial().getParam("BaseColor");
            require(baseColor != null && ((ColorRGBA) baseColor.getValue()).equals(
                    new ColorRGBA(0.1f, 0.8f, 0.2f, 1f)), "PBR BaseColor mismatch");
            MatParam roughness = geometry.getMaterial().getParam("Roughness");
            require(roughness != null && Math.abs((Float) roughness.getValue() - 0.75f) < 0.0001f,
                    "Smoothness to Roughness mismatch");
            Node instance = requireNode(converted.getChild("InstancedCube"),
                    "Prefab instance missing");
            requireVector(instance.getLocalTranslation(), new Vector3f(5, 0, -2),
                    "Prefab transform override");
            require(Math.abs(instance.getLocalRotation().getX() + 0.1f) < 0.0001f
                    && Math.abs(instance.getLocalRotation().getY() + 0.2f) < 0.0001f
                    && Math.abs(instance.getLocalRotation().getZ() - 0.3f) < 0.0001f,
                    "Prefab quaternion override was normalized between components");
            Geometry instanceGeometry = (Geometry) instance.getChild(0);
            require(instanceGeometry.getMaterial().getAdditionalRenderState().getBlendMode()
                    == com.jme3.material.RenderState.BlendMode.Alpha,
                    "Prefab material override/alpha blend missing");
            require(converted.getLocalLightList().size() >= 1, "Directional light missing");

            Path output = directory.resolve("RoundTrip.j3o");
            BinaryExporter.getInstance().save(converted, output.toFile());
            root.refresh();
            Node loaded = requireNode(manager.loadModel("RoundTrip.j3o"), "J3O root is not a Node");
            require(loaded.getChild("Parent") != null && loaded.getChild("InstancedCube") != null,
                    "J3O round trip lost scene nodes");
            verifyRegressions(directory, root, manager);
            System.out.println("UNITY_SCENE_IMPORTER_SMOKE_PASS");
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    private static void verifyRegressions(Path directory, FileObject root,
            ProjectAssetManager manager) throws Exception {
        write(directory, "Legacy.obj", "# metadata test");
        write(directory, "Legacy.obj.meta", "ModelImporter:\n  fileIDToRecycleName:\n    4300000: LegacyMesh\n");
        write(directory, "Modern.obj", "# metadata test");
        write(directory, "Modern.obj.meta", "ModelImporter:\n  internalIDToNameTable:\n  - first: {43: 4300001}\n    second: ModernMesh\n");
        String jmeGuid = "abcdefabcdefabcdefabcdefabcdefab";
        write(directory, "Materials/Existing.j3m", "Material Existing : Common/MatDefs/Misc/Unshaded.j3md {\nMaterialParameters {\nColor : 1 0 0 1\n}\n}\n");
        write(directory, "Materials/Existing.j3m.meta", "guid: " + jmeGuid + "\n");
        root.refresh();
        UnityAssetIndex index = new UnityAssetIndex(root);
        com.jme3.material.Material existing = new UnityMaterialReader(manager, index,
                message -> { throw new AssertionError(message); }).read(
                        new UnityYaml.Ref(2100000, jmeGuid, 2), null);
        require(existing != null && "Existing".equals(existing.getName()),
                "Existing J3M material loading");
        require("LegacyMesh".equals(index.meshName(root.getFileObject("Legacy.obj"), 4300000)),
                "Legacy numeric mesh ID");
        require("ModernMesh".equals(index.meshName(root.getFileObject("Modern.obj"), 4300001)),
                "Modern numeric mesh class ID");
        for (long id : new long[]{10206, 10208, 10209, 10210}) {
            Spatial primitive = UnityPrimitives.create(id);
            primitive.updateGeometricState();
            requireVector(primitive.getWorldBound().getCenter(), Vector3f.ZERO,
                    "Primitive origin " + id);
            if (id == 10206) {
                com.jme3.bounding.BoundingBox bounds = (com.jme3.bounding.BoundingBox) primitive.getWorldBound();
                require(Math.abs(bounds.getYExtent() - 1f) < 0.0001f, "Cylinder Y axis");
            }
        }
        write(directory, "Disabled.unity", prefab().replace("MeshRenderer:\n",
                "MeshRenderer:\n  m_Enabled: 0\n"));
        root.refresh();
        Node disabled = new UnitySceneLoader().load(root.getFileObject("Disabled.unity"), manager);
        require(((Node) disabled.getChild("PrefabCube")).getQuantity() == 0,
                "Disabled renderer must not create geometry");
        write(directory, "Cycle.unity", scene().replace(
                "m_Father: {fileID: 0}", "m_Father: {fileID: 8}"));
        root.refresh();
        Node cycle = new UnitySceneLoader().load(root.getFileObject("Cycle.unity"), manager);
        cycle.updateGeometricState();
        require(cycle.getChild("Cube") != null, "Cyclic hierarchy fallback lost Cube");
        try {
            UnityYaml.parse("not a Unity scene");
            throw new AssertionError("Invalid scene accepted");
        } catch (java.io.IOException expected) {
            // Unsupported content must not be silently exported as an empty scene.
        }
    }

    private static void write(Path root, String path, String content) throws Exception {
        Files.writeString(root.resolve(path), content);
    }

    private static Node requireNode(Spatial spatial, String message) {
        require(spatial instanceof Node, message);
        return (Node) spatial;
    }

    private static void requireVector(Vector3f actual, Vector3f expected, String message) {
        require(actual.distance(expected) < 0.0001f,
                message + ": expected=" + expected + ", actual=" + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new RuntimeException(message);
        }
    }

    private static String material() {
        return """
                %YAML 1.1
                %TAG !u! tag:unity3d.com,2011:
                --- !u!21 &2100000
                Material:
                  m_Name: Green
                  m_Shader: {fileID: 46, guid: 0000000000000000f000000000000000, type: 0}
                  m_SavedProperties:
                    serializedVersion: 3
                    m_TexEnvs: []
                    m_Floats:
                    - _Metallic: 0.2
                    - _Glossiness: 0.25
                    m_Colors:
                    - _Color: {r: 0.1, g: 0.8, b: 0.2, a: 1}
                """;
    }

    private static String glassMaterial() {
        return """
                %YAML 1.1
                %TAG !u! tag:unity3d.com,2011:
                --- !u!21 &2100000
                Material:
                  m_Name: Glass
                  m_Shader: {fileID: 46, guid: 0000000000000000f000000000000000, type: 0}
                  m_SavedProperties:
                    m_TexEnvs: []
                    m_Floats:
                    - first: {name: _Metallic}
                      second: 0
                    - first: {name: _Glossiness}
                      second: 0.8
                    - first: {name: _Mode}
                      second: 3
                    m_Colors:
                    - first: {name: _Color}
                      second: {r: 0.2, g: 0.3, b: 0.4, a: 0.5}
                """;
    }

    private static String prefab() {
        return """
                %YAML 1.1
                %TAG !u! tag:unity3d.com,2011:
                --- !u!1 &100000
                GameObject:
                  m_Name: PrefabCube
                  m_IsActive: 1
                  m_Component:
                  - component: {fileID: 400000}
                  - component: {fileID: 3300000}
                  - component: {fileID: 2300000}
                --- !u!4 &400000
                Transform:
                  m_GameObject: {fileID: 100000}
                  m_LocalRotation: {x: 0, y: 0, z: 0, w: 1}
                  m_LocalPosition: {x: 0, y: 0, z: 0}
                  m_LocalScale: {x: 1, y: 1, z: 1}
                  m_Father: {fileID: 0}
                --- !u!33 &3300000
                MeshFilter:
                  m_GameObject: {fileID: 100000}
                  m_Mesh: {fileID: 10202, guid: 0000000000000000e000000000000000, type: 0}
                --- !u!23 &2300000
                MeshRenderer:
                  m_GameObject: {fileID: 100000}
                  m_Materials:
                  - {fileID: 2100000, guid: ${MATERIAL_GUID}, type: 2}
                """.replace("${MATERIAL_GUID}", MATERIAL_GUID);
    }

    private static String scene() {
        return """
                %YAML 1.1
                %TAG !u! tag:unity3d.com,2011:
                --- !u!1 &1
                GameObject:
                  m_Name: Parent
                  m_IsActive: 1
                  m_Component:
                  - component: {fileID: 4}
                --- !u!4 &4
                Transform:
                  m_GameObject: {fileID: 1}
                  m_LocalRotation: {x: 0, y: 0, z: 0, w: 1}
                  m_LocalPosition: {x: 1, y: 2, z: 3}
                  m_LocalScale: {x: 1, y: 1, z: 1}
                  m_Children:
                  - {fileID: 8}
                  m_Father: {fileID: 0}
                --- !u!1 &5
                GameObject:
                  m_Name: Cube
                  m_IsActive: 1
                  m_Component:
                  - component: {fileID: 8}
                  - component: {fileID: 33}
                  - component: {fileID: 23}
                --- !u!4 &8
                Transform:
                  m_GameObject: {fileID: 5}
                  m_LocalRotation: {x: 0, y: 0, z: 0, w: 1}
                  m_LocalPosition: {x: 0, y: 1, z: 0}
                  m_LocalScale: {x: 1, y: 1, z: 1}
                  m_Father: {fileID: 4}
                --- !u!33 &33
                MeshFilter:
                  m_GameObject: {fileID: 5}
                  m_Mesh: {fileID: 10202, guid: 0000000000000000e000000000000000, type: 0}
                --- !u!23 &23
                MeshRenderer:
                  m_GameObject: {fileID: 5}
                  m_Materials:
                  - {fileID: 2100000, guid: ${MATERIAL_GUID}, type: 2}
                --- !u!1 &100
                GameObject:
                  m_Name: Sun
                  m_IsActive: 1
                  m_Component:
                  - component: {fileID: 104}
                  - component: {fileID: 108}
                --- !u!4 &104
                Transform:
                  m_GameObject: {fileID: 100}
                  m_LocalRotation: {x: 0, y: 0, z: 0, w: 1}
                  m_LocalPosition: {x: 0, y: 5, z: 0}
                  m_LocalScale: {x: 1, y: 1, z: 1}
                  m_Father: {fileID: 0}
                --- !u!108 &108
                Light:
                  m_GameObject: {fileID: 100}
                  m_Type: 1
                  m_Color: {r: 1, g: 1, b: 1, a: 1}
                  m_Intensity: 1
                --- !u!1001 &1000
                PrefabInstance:
                  m_Modification:
                    m_TransformParent: {fileID: 0}
                    m_Modifications:
                    - target: {fileID: 100000, guid: ${PREFAB_GUID}, type: 3}
                      propertyPath: m_Name
                      value: InstancedCube
                    - target: {fileID: 400000, guid: ${PREFAB_GUID}, type: 3}
                      propertyPath: m_LocalPosition.x
                      value: 5
                    - target: {fileID: 400000, guid: ${PREFAB_GUID}, type: 3}
                      propertyPath: m_LocalPosition.z
                      value: 2
                    - target: {fileID: 400000, guid: ${PREFAB_GUID}, type: 3}
                      propertyPath: m_LocalRotation.x
                      value: 0.1
                    - target: {fileID: 400000, guid: ${PREFAB_GUID}, type: 3}
                      propertyPath: m_LocalRotation.y
                      value: 0.2
                    - target: {fileID: 400000, guid: ${PREFAB_GUID}, type: 3}
                      propertyPath: m_LocalRotation.z
                      value: 0.3
                    - target: {fileID: 400000, guid: ${PREFAB_GUID}, type: 3}
                      propertyPath: m_LocalRotation.w
                      value: 0.92736185
                    - target: {fileID: 2300000, guid: ${PREFAB_GUID}, type: 3}
                      propertyPath: m_Materials.Array.data[0]
                      objectReference: {fileID: 2100000, guid: ${GLASS_GUID}, type: 2}
                    m_RemovedComponents: []
                  m_SourcePrefab: {fileID: 100100000, guid: ${PREFAB_GUID}, type: 3}
                """.replace("${MATERIAL_GUID}", MATERIAL_GUID)
                .replace("${PREFAB_GUID}", PREFAB_GUID)
                .replace("${GLASS_GUID}", GLASS_GUID);
    }
}

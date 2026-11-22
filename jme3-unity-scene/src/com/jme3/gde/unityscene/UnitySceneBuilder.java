package com.jme3.gde.unityscene;

import com.jme3.asset.AssetManager;
import com.jme3.asset.ModelKey;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.light.Light;
import com.jme3.light.PointLight;
import com.jme3.light.SpotLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Transform;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openide.filesystems.FileObject;

/** Builds a JME scene graph from Unity documents and their referenced project assets. */
final class UnitySceneBuilder {
    private static final Logger LOG = Logger.getLogger(UnitySceneBuilder.class.getName());
    private static final Set<String> IGNORED_DOCUMENTS = Set.of(
            "OcclusionCullingSettings", "NavMeshSettings", "LightmapSettings", "SceneRoots");
    private final AssetManager assets;
    private final UnityAssetIndex index;
    private final UnityMaterialReader materials;
    private final List<PendingLight> pendingLights = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final Set<String> onceWarnings = new HashSet<>();
    private final Set<String> prefabStack = new HashSet<>();
    private Material fallbackMaterial;

    UnitySceneBuilder(AssetManager assets, UnityAssetIndex index) {
        this.assets = assets;
        this.index = index;
        this.materials = new UnityMaterialReader(assets, index, this::warn);
    }

    private record PendingLight(Light light, Node node) {
    }

    private static final class BuildResult {
        private final Node root;
        private final Map<Long, Node> objects = new HashMap<>();
        private Node primaryRoot;

        private BuildResult(String name) {
            root = new Node(name);
        }
    }

    Node build(FileObject source) throws IOException {
        prefabStack.add(index.path(source));
        BuildResult result;
        try {
            result = buildAsset(source, 0);
        } finally {
            prefabStack.clear();
        }
        Node root = result.root;
        root.updateGeometricState();
        for (PendingLight pending : pendingLights) {
            if (!isEffectivelyActive(pending.node())) {
                continue;
            }
            if (pending.light() instanceof PointLight point) {
                point.setPosition(pending.node().getWorldTranslation().clone());
            } else if (pending.light() instanceof SpotLight spot) {
                spot.setPosition(pending.node().getWorldTranslation().clone());
                spot.setDirection(UnityCoordinates.forward(pending.node().getWorldRotation()));
            } else if (pending.light() instanceof DirectionalLight directional) {
                directional.setDirection(UnityCoordinates.forward(
                        pending.node().getWorldRotation()));
            }
            root.addLight(pending.light());
        }
        root.setUserData("unity.import.warning.count", warnings.size());
        root.setUserData("unity.import.warnings", String.join("\n", warnings));
        root.setUserData("unity.coordinates", "position=(x,y,-z); quaternion=(-x,-y,z,w)");
        return root;
    }

    private boolean isEffectivelyActive(Spatial spatial) {
        Spatial current = spatial;
        while (current != null) {
            Boolean active = current.getUserData("unity.active");
            if (active != null && !active) {
                return false;
            }
            current = current.getParent();
        }
        return true;
    }

    private BuildResult buildAsset(FileObject source, int depth) throws IOException {
        BuildResult result = buildDocuments(UnityYaml.parse(source.asText("UTF-8")),
                source.getName(), depth);
        result.root.setUserData("unity.source", index.path(source));
        return result;
    }

    private BuildResult buildDocuments(List<UnityYaml.Document> documents, String name,
            int depth) {
        BuildResult result = new BuildResult(name);
        Map<Long, List<UnityYaml.Document>> components = new HashMap<>();
        List<UnityYaml.Document> gameObjects = new ArrayList<>();
        for (UnityYaml.Document document : documents) {
            if (document.classId() == 1 && !document.stripped()) {
                gameObjects.add(document);
            }
            long gameObject = UnityYaml.ref(document.values().get("m_GameObject")).fileId();
            if (gameObject != 0) {
                components.computeIfAbsent(gameObject, key -> new ArrayList<>()).add(document);
            }
        }
        for (UnityYaml.Document gameObject : gameObjects) {
            List<UnityYaml.Document> parts = components.getOrDefault(
                    gameObject.fileId(), List.of());
            Node node = createGameObject(gameObject, parts);
            result.objects.put(gameObject.fileId(), node);
            for (UnityYaml.Document part : parts) {
                // Overrides for a component, such as MeshRenderer materials, apply to its existing node.
                result.objects.put(part.fileId(), node);
            }
        }
        for (UnityYaml.Document gameObject : gameObjects) {
            Node node = result.objects.get(gameObject.fileId());
            UnityYaml.Document transform = findTransform(
                    components.getOrDefault(gameObject.fileId(), List.of()));
            UnityYaml.Ref parent = transform == null ? new UnityYaml.Ref(0, null, 0)
                    : UnityYaml.ref(transform.values().get("m_Father"));
            Node parentNode = result.objects.get(parent.fileId());
            if (parent.isZero()) {
                result.root.attachChild(node);
                if (result.primaryRoot == null) {
                    result.primaryRoot = node;
                }
            } else if (parentNode != null && !wouldCreateCycle(node, parentNode)) {
                parentNode.attachChild(node);
            } else {
                warn("Unresolved parent for " + node.getName() + " (Transform fileID="
                        + parent.fileId() + "); attached at the asset root");
                result.root.attachChild(node);
                if (result.primaryRoot == null) {
                    result.primaryRoot = node;
                }
            }
        }
        for (UnityYaml.Document document : documents) {
            if (document.classId() == 1001 && !document.stripped()) {
                instantiatePrefab(document, result, depth);
            } else if (document.classId() == 104) {
                addAmbient(document, result.root);
            } else if (!IGNORED_DOCUMENTS.contains(document.type())
                    && UnityYaml.ref(document.values().get("m_GameObject")).isZero()
                    && document.classId() != 1 && document.classId() != 1001) {
                warnOnce("document:" + document.type(),
                        "Ignored Unity scene document: " + document.type());
            }
        }
        return result;
    }

    private Node createGameObject(UnityYaml.Document gameObject,
            List<UnityYaml.Document> components) {
        String name = UnityYaml.stringValue(gameObject.values().get("m_Name"),
                "GameObject-" + gameObject.fileId());
        Node node = new Node(name);
        node.setUserData("unity.fileID", gameObject.fileId());
        boolean active = UnityYaml.booleanValue(gameObject.values().get("m_IsActive"), true);
        node.setUserData("unity.active", active);
        if (!active) {
            node.setCullHint(Spatial.CullHint.Always);
        }
        UnityYaml.Document transform = findTransform(components);
        if (transform != null) {
            applyTransform(node, transform.values());
        }
        UnityYaml.Document filter = findComponent(components, 33);
        UnityYaml.Document renderer = findComponent(components, 23);
        UnityYaml.Document skinned = findComponent(components, 137);
        if (renderer != null && filter != null) {
            addRenderer(node, filter.values().get("m_Mesh"), renderer, false);
        } else if (skinned != null) {
            warnOnce("skinned", "SkinnedMeshRenderer uses a clone of the complete source "
                    + "model; Unity bone remapping is not performed");
            addRenderer(node, skinned.values().get("m_Mesh"), skinned, true);
        }
        for (UnityYaml.Document component : components) {
            if (component.classId() == 108 && active) {
                createLight(component, node);
            } else if (component.classId() == 20) {
                preserveCamera(component, node);
            } else if (!isTransform(component) && component.classId() != 33
                    && component.classId() != 23 && component.classId() != 137
                    && component.classId() != 108 && component.classId() != 20) {
                warnOnce("component:" + component.type(), "Ignored unsupported Unity "
                        + "component: " + component.type());
            }
        }
        return node;
    }

    private void addRenderer(Node node, Object meshReference, UnityYaml.Document renderer,
            boolean completeModel) {
        if (!UnityYaml.booleanValue(renderer.values().get("m_Enabled"), true)) {
            return;
        }
        Spatial model = loadModel(UnityYaml.ref(meshReference), completeModel);
        if (model == null) {
            return;
        }
        applyMaterials(model, UnityYaml.list(renderer.values().get("m_Materials")));
        node.attachChild(model);
    }

    private Spatial loadModel(UnityYaml.Ref reference, boolean completeModel) {
        if (reference == null || reference.isZero()) {
            return null;
        }
        if (isBuiltIn(reference.guid())) {
            Spatial primitive = UnityPrimitives.create(reference.fileId());
            if (primitive == null) {
                warn("Unsupported Unity built-in Mesh fileID=" + reference.fileId());
            }
            return primitive;
        }
        FileObject file = index.find(reference);
        if (file == null) {
            warn("Mesh/model GUID is missing from the project: " + reference.guid());
            return null;
        }
        String path = index.path(file);
        if (!isModel(file)) {
            warn("Mesh reference does not point to a supported model: " + path);
            return null;
        }
        try {
            Spatial loaded = assets.loadModel(new ModelKey(path));
            Spatial selected = loaded;
            if (!completeModel) {
                String meshName = index.meshName(file, reference.fileId());
                if (meshName != null && !"//RootNode".equals(meshName)) {
                    Spatial named = findByName(loaded, meshName);
                    if (named != null) {
                        selected = named;
                    } else {
                        warn("Mesh '" + meshName + "' was not found in " + path
                                + "; using the complete model");
                    }
                }
            }
            Spatial copy = selected.clone();
            if (selected != loaded) {
                // Include the model root when baking the selected child's ancestor transforms.
                loaded.updateGeometricState();
                Transform baked = selected.getWorldTransform().clone();
                copy.setLocalTransform(baked);
            }
            return copy;
        } catch (IOException | RuntimeException failure) {
            warn("Cannot load model " + path + ": " + failure.getMessage());
            return null;
        }
    }

    private boolean wouldCreateCycle(Node node, Node parent) {
        for (Node current = parent; current != null; current = current.getParent()) {
            if (current == node) {
                return true;
            }
        }
        return false;
    }

    private void applyMaterials(Spatial model, List<Object> materialReferences) {
        List<Geometry> geometries = new ArrayList<>();
        model.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry geometry) {
                geometries.add(geometry);
            }
        });
        if (materialReferences.size() > 1 && materialReferences.size() != geometries.size()) {
            warn("Unity submesh material count does not match imported Geometry count on "
                    + model.getName() + "; slots use traversal order as a fallback");
        }
        for (int i = 0; i < geometries.size(); i++) {
            Geometry geometry = geometries.get(i);
            Material fallback = geometry.getMaterial() == null ? fallback() : geometry.getMaterial();
            Material material = fallback;
            if (!materialReferences.isEmpty()) {
                int materialIndex = Math.min(i, materialReferences.size() - 1);
                material = materials.read(UnityYaml.ref(materialReferences.get(materialIndex)),
                        fallback);
            }
            applyMaterial(geometry, material);
        }
    }

    private void applyMaterial(Geometry geometry, Material material) {
        if (material == null) {
            return;
        }
        geometry.setMaterial(material);
        if (material.isTransparent()) {
            geometry.setQueueBucket(RenderQueue.Bucket.Transparent);
        } else if (geometry.getQueueBucket() == RenderQueue.Bucket.Transparent) {
            geometry.setQueueBucket(RenderQueue.Bucket.Inherit);
        }
    }

    private Material fallback() {
        if (fallbackMaterial == null) {
            fallbackMaterial = new Material(assets, "Common/MatDefs/Light/PBRLighting.j3md");
            fallbackMaterial.setName("Unity Import Fallback");
            fallbackMaterial.setColor("BaseColor", new ColorRGBA(0.7f, 0.7f, 0.7f, 1f));
            fallbackMaterial.setFloat("Metallic", 0f);
            fallbackMaterial.setFloat("Roughness", 0.8f);
        }
        return fallbackMaterial;
    }

    private void createLight(UnityYaml.Document source, Node node) {
        if (!UnityYaml.booleanValue(source.values().get("m_Enabled"), true)) {
            return;
        }
        int type = (int) UnityYaml.longValue(source.values().get("m_Type"), 1);
        ColorRGBA color = UnityYaml.color(source.values().get("m_Color"), ColorRGBA.White);
        float intensity = UnityYaml.floatValue(source.values().get("m_Intensity"), 1f);
        color.multLocal(intensity);
        float range = UnityYaml.floatValue(source.values().get("m_Range"), 10f);
        Light light;
        if (type == 1) {
            light = new DirectionalLight();
        } else if (type == 2) {
            PointLight point = new PointLight();
            point.setRadius(range);
            light = point;
        } else if (type == 0) {
            SpotLight spot = new SpotLight();
            spot.setSpotRange(range);
            float outer = UnityYaml.floatValue(source.values().get("m_SpotAngle"), 30f);
            float inner = UnityYaml.floatValue(source.values().get("m_InnerSpotAngle"),
                    outer * 0.8f);
            spot.setSpotOuterAngle(outer * FastMath.DEG_TO_RAD * 0.5f);
            spot.setSpotInnerAngle(inner * FastMath.DEG_TO_RAD * 0.5f);
            light = spot;
        } else {
            warnOnce("light:" + type, "Ignored unsupported Unity area light type " + type);
            return;
        }
        light.setName(node.getName() + " Light");
        light.setColor(color);
        pendingLights.add(new PendingLight(light, node));
    }

    private void addAmbient(UnityYaml.Document settings, Node root) {
        Object value = settings.values().get("m_AmbientSkyColor");
        if (value == null) {
            value = settings.values().get("m_AmbientLight");
        }
        ColorRGBA color = UnityYaml.color(value, ColorRGBA.DarkGray);
        color.multLocal(UnityYaml.floatValue(settings.values().get("m_AmbientIntensity"), 1f));
        AmbientLight ambient = new AmbientLight();
        ambient.setName("Unity Ambient");
        ambient.setColor(color);
        root.addLight(ambient);
        if (!UnityYaml.ref(settings.values().get("m_SkyboxMaterial")).isZero()) {
            warnOnce("skybox", "Unity skybox material is not converted to a JME sky");
        }
    }

    private void preserveCamera(UnityYaml.Document camera, Node node) {
        node.setUserData("unity.camera.fov",
                UnityYaml.floatValue(camera.values().get("field of view"), 60f));
        node.setUserData("unity.camera.near",
                UnityYaml.floatValue(camera.values().get("near clip plane"), 0.3f));
        node.setUserData("unity.camera.far",
                UnityYaml.floatValue(camera.values().get("far clip plane"), 1000f));
        warnOnce("camera", "Unity cameras are preserved as node metadata; the SDK viewport "
                + "camera is not changed");
    }

    private void instantiatePrefab(UnityYaml.Document instance, BuildResult scene, int depth) {
        if (depth >= 16) {
            warn("Prefab nesting limit reached at fileID=" + instance.fileId());
            return;
        }
        UnityYaml.Ref source = UnityYaml.ref(instance.values().get("m_SourcePrefab"));
        if (source.isZero()) {
            source = UnityYaml.ref(instance.values().get("m_ParentPrefab"));
        }
        FileObject file = index.find(source);
        if (file == null) {
            warn("Prefab GUID is missing from the project: " + source.guid());
            return;
        }
        String path = index.path(file);
        if (!prefabStack.add(path)) {
            warn("Prefab reference cycle detected at " + path);
            return;
        }
        try {
            BuildResult prefab = buildAsset(file, depth + 1);
            Node node = prefab.primaryRoot != null && prefab.root.getQuantity() == 1
                    ? prefab.primaryRoot : prefab.root;
            Map<String, Object> modification = UnityYaml.map(
                    instance.values().get("m_Modification"));
            applyPrefabModifications(prefab, modification);
            Node parent = scene.root;
            UnityYaml.Ref parentReference = UnityYaml.ref(modification.get("m_TransformParent"));
            Node requested = scene.objects.get(parentReference.fileId());
            if (requested != null) {
                parent = requested;
            }
            parent.attachChild(node);
        } catch (IOException | RuntimeException failure) {
            warn("Cannot instantiate prefab " + path + ": " + failure.getMessage());
        } finally {
            prefabStack.remove(path);
        }
    }

    private void applyPrefabModifications(BuildResult prefab,
            Map<String, Object> modification) {
        List<Object> modifications = UnityYaml.list(modification.get("m_Modifications"));
        Map<Long, Map<String, Float>> rotations = new HashMap<>();
        for (Object item : modifications) {
            Map<String, Object> entry = UnityYaml.map(item);
            String path = UnityYaml.stringValue(entry.get("propertyPath"), "");
            if (path.startsWith("m_LocalRotation.")) {
                long targetId = UnityYaml.ref(entry.get("target")).fileId();
                rotations.computeIfAbsent(targetId, key -> new HashMap<>()).put(
                        path.substring(path.length() - 1),
                        UnityYaml.floatValue(entry.get("value"), 0));
            }
        }
        // Apply all Unity components before converting and normalizing the result.
        for (Map.Entry<Long, Map<String, Float>> override : rotations.entrySet()) {
            Node target = prefab.objects.get(override.getKey());
            if (target == null) {
                continue;
            }
            Quaternion unity = UnityCoordinates.toUnity(target.getLocalRotation());
            for (Map.Entry<String, Float> component : override.getValue().entrySet()) {
                setUnityComponent(unity, component.getKey(), component.getValue());
            }
            target.setLocalRotation(UnityCoordinates.rotation(unity));
        }
        for (Object item : modifications) {
            Map<String, Object> entry = UnityYaml.map(item);
            long targetId = UnityYaml.ref(entry.get("target")).fileId();
            Node target = prefab.objects.get(targetId);
            String path = UnityYaml.stringValue(entry.get("propertyPath"), "");
            if (target == null) {
                warn("Prefab override target not found: fileID=" + targetId
                        + ", property=" + path);
                continue;
            }
            Object value = entry.get("value");
            if (path.startsWith("m_LocalPosition.")) {
                Vector3f current = target.getLocalTranslation();
                setComponent(current, path.substring(path.length() - 1),
                        UnityYaml.floatValue(value, 0), true);
                target.setLocalTranslation(current);
            } else if (path.startsWith("m_LocalScale.")) {
                Vector3f current = target.getLocalScale();
                setComponent(current, path.substring(path.length() - 1),
                        UnityYaml.floatValue(value, 1), false);
                target.setLocalScale(current);
            } else if (path.startsWith("m_LocalRotation.")) {
                // The full quaternion was already applied above.
            } else if ("m_Name".equals(path)) {
                target.setName(UnityYaml.stringValue(value, target.getName()));
            } else if ("m_IsActive".equals(path)) {
                boolean active = UnityYaml.booleanValue(value, true);
                target.setUserData("unity.active", active);
                target.setCullHint(active ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
            } else if (path.startsWith("m_Materials.Array.data[")) {
                int geometryIndex = arrayIndex(path);
                applyMaterialOverride(target, UnityYaml.ref(entry.get("objectReference")),
                        geometryIndex);
            } else if (!path.startsWith("m_LocalEulerAnglesHint")
                    && !"m_RootOrder".equals(path)) {
                warnOnce("prefab-property:" + path,
                        "Ignored unsupported prefab override property: " + path);
            }
        }
        if (!UnityYaml.list(modification.get("m_RemovedComponents")).isEmpty()) {
            warnOnce("prefab-remove", "Prefab removed-component overrides are not converted");
        }
    }

    private void applyMaterialOverride(Node target, UnityYaml.Ref reference, int index) {
        List<Geometry> geometries = new ArrayList<>();
        target.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry geometry) {
                geometries.add(geometry);
            }
        });
        if (geometries.isEmpty()) {
            return;
        }
        int selected = Math.max(0, Math.min(index, geometries.size() - 1));
        Geometry geometry = geometries.get(selected);
        applyMaterial(geometry, materials.read(reference, geometry.getMaterial()));
    }

    private int arrayIndex(String path) {
        int start = path.indexOf('[');
        int end = path.indexOf(']', start + 1);
        if (start < 0 || end < 0) {
            return 0;
        }
        return (int) UnityYaml.longValue(path.substring(start + 1, end), 0);
    }

    private void setComponent(Vector3f vector, String axis, float value, boolean negateZ) {
        switch (axis) {
            case "x" -> vector.x = value;
            case "y" -> vector.y = value;
            case "z" -> vector.z = negateZ ? -value : value;
            default -> {
            }
        }
    }

    private void setUnityComponent(Quaternion quaternion, String axis, float value) {
        float x = quaternion.getX();
        float y = quaternion.getY();
        float z = quaternion.getZ();
        float w = quaternion.getW();
        switch (axis) {
            case "x" -> x = value;
            case "y" -> y = value;
            case "z" -> z = value;
            case "w" -> w = value;
            default -> {
            }
        }
        quaternion.set(x, y, z, w);
    }

    private void applyTransform(Node node, Map<String, Object> transform) {
        node.setLocalTranslation(UnityCoordinates.position(transform.get("m_LocalPosition")));
        node.setLocalRotation(UnityCoordinates.rotation(transform.get("m_LocalRotation")));
        node.setLocalScale(UnityCoordinates.scale(transform.get("m_LocalScale")));
    }

    private UnityYaml.Document findTransform(List<UnityYaml.Document> components) {
        for (UnityYaml.Document component : components) {
            if (isTransform(component)) {
                return component;
            }
        }
        return null;
    }

    private boolean isTransform(UnityYaml.Document document) {
        return document.classId() == 4 || document.classId() == 224;
    }

    private UnityYaml.Document findComponent(List<UnityYaml.Document> components, int classId) {
        for (UnityYaml.Document component : components) {
            if (component.classId() == classId) {
                return component;
            }
        }
        return null;
    }

    private Spatial findByName(Spatial root, String name) {
        Spatial[] result = new Spatial[1];
        root.depthFirstTraversal(spatial -> {
            if (result[0] == null && name.equals(spatial.getName())) {
                result[0] = spatial;
            }
        });
        return result[0];
    }

    private boolean isBuiltIn(String guid) {
        return "0000000000000000e000000000000000".equals(guid)
                || "0000000000000000f000000000000000".equals(guid);
    }

    private boolean isModel(FileObject file) {
        String extension = file.getExt().toLowerCase();
        return Set.of("j3o", "fbx", "obj", "gltf", "glb", "blend", "meshxml")
                .contains(extension) || file.getNameExt().toLowerCase().endsWith(".mesh.xml");
    }

    private void warnOnce(String key, String message) {
        if (onceWarnings.add(key)) {
            warn(message);
        }
    }

    private void warn(String message) {
        warnings.add(message);
        if (warnings.size() <= 200) {
            LOG.log(Level.WARNING, "[Unity import] {0}", message);
        }
    }
}

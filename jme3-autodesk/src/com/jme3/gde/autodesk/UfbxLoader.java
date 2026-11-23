package com.jme3.gde.autodesk;

import com.jme3.anim.AnimClip;
import com.jme3.anim.AnimComposer;
import com.jme3.anim.AnimTrack;
import com.jme3.anim.Armature;
import com.jme3.anim.Joint;
import com.jme3.anim.SkinningControl;
import com.jme3.anim.TransformTrack;
import com.jme3.anim.util.HasLocalTransform;
import com.jme3.asset.AssetInfo;
import com.jme3.asset.AssetLoader;
import com.jme3.asset.AssetNotFoundException;
import com.jme3.asset.TextureKey;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix4f;
import com.jme3.math.Quaternion;
import com.jme3.math.Transform;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.plugins.AWTLoader;
import com.jme3.util.BufferUtils;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.openide.modules.InstalledFileLocator;

/**
 * Imports FBX through the SDK's native ufbx library. J3O serialization and DDS
 * preparation are performed by the normal SDK conversion action.
 *
 * <pre>
 * 第一步：用 tools/build-ufbx-jni.ps1 构建并安装当前平台的原生库。
 * 第二步：在项目资产目录中放入 FBX 和其外置纹理，右键 Convert to J3O。
 * 第三步：用动画面板检查导入的片段，纹理 DDS 由 SDK 在保存模型时生成。
 * 第四步：需要旧导入器时，以 -J-Djme.sdk.fbx.backend=legacy 重启 SDK。
 * </pre>
 */
public final class UfbxLoader implements AssetLoader {
    private static final int TRANSFER_LIMIT = 512 * 1024 * 1024;
    private static boolean loaded;

    @Override
    public Node load(AssetInfo info) throws IOException {
        loadLibrary();
        try (InputStream source = info.openStream()) {
            byte[] bytes = source.readNBytes(TRANSFER_LIMIT + 1);
            if (bytes.length == 0 || bytes.length > TRANSFER_LIMIT) {
                throw new IOException("FBX is empty or exceeds the 512 MiB import limit");
            }
            byte[] packet = readScene(bytes);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
                return new SceneReader(info, input).read();
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw new IOException("ufbx import failed for " + info.getKey().getName(), ex);
        }
    }

    private static synchronized void loadLibrary() throws IOException {
        if (loaded) {
            return;
        }
        String override = System.getProperty("jme.sdk.fbx.library");
        String library = System.mapLibraryName("jme-ufbx");
        File installed = override == null ? InstalledFileLocator.getDefault().locate(
                "lib/ufbx/" + host() + "/" + library, "com.jme3.gde.autodesk", false)
                : new File(override);
        if (installed == null || !installed.isFile()) {
            throw new IOException("Install the ufbx JNI library for " + host()
                    + " or select -J-Djme.sdk.fbx.backend=legacy");
        }
        try {
            System.load(installed.getCanonicalPath());
            loaded = true;
        } catch (LinkageError | SecurityException ex) {
            throw new IOException("Cannot load ufbx JNI library " + installed, ex);
        }
    }

    private static String host() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        os = os.startsWith("windows") ? "windows" : os.startsWith("mac") ? "macos" : os;
        arch = switch (arch) {
            case "amd64" -> "x86_64";
            case "aarch64" -> "arm64";
            default -> arch;
        };
        return os + "-" + arch;
    }

    private static native byte[] readScene(byte[] source) throws IOException;

    private static final class SceneReader {
        private final AssetInfo info;
        private final DataInputStream input;
        private final List<Joint[]> rigs = new ArrayList<>();
        private Node[] nodes;
        private int[] parents;
        private SourceTexture[] images;
        private Material[] materials;

        SceneReader(AssetInfo info, DataInputStream input) {
            this.info = info;
            this.input = input;
        }

        Node read() throws IOException {
            if (input.readInt() != 0x55464231) {
                throw new IOException("Incompatible ufbx JNI scene format");
            }
            Node root = new Node(info.getKey().getName());
            nodes = new Node[count(40)];
            parents = new int[nodes.length];
            for (int i = 0; i < nodes.length; ++i) {
                nodes[i] = new Node(string());
                parents[i] = input.readInt();
                nodes[i].setLocalTransform(transform());
            }
            for (int i = 0; i < nodes.length; ++i) {
                Node parent = parents[i] < 0 ? root : nodes[parents[i]];
                parent.attachChild(nodes[i]);
            }
            images = new SourceTexture[count(8)];
            for (int i = 0; i < images.length; ++i) {
                images[i] = new SourceTexture(string(), bytes());
            }
            materials = new Material[count(40)];
            for (int i = 0; i < materials.length; ++i) {
                materials[i] = material();
            }
            int meshCount = count(12);
            for (int i = 0; i < meshCount; ++i) {
                mesh(root);
            }
            animations(root);
            if (input.read() != -1) {
                throw new IOException("Unexpected trailing ufbx scene data");
            }
            return root;
        }

        private Material material() throws IOException {
            Material material = defaultMaterial();
            material.setName(string());
            ColorRGBA color = new ColorRGBA(input.readFloat(), input.readFloat(),
                    input.readFloat(), input.readFloat());
            material.setColor("BaseColor", color);
            material.setFloat("Metallic", input.readFloat());
            material.setFloat("Roughness", input.readFloat());
            texture(material, "BaseColorMap");
            texture(material, "NormalMap");
            texture(material, "EmissiveMap");
            if (color.a < 1f) {
                material.setTransparent(true);
                material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            }
            return material;
        }

        private Material defaultMaterial() {
            Material material = new Material(info.getManager(), "Common/MatDefs/Light/PBRLighting.j3md");
            material.setFloat("Metallic", 0f);
            material.setFloat("Roughness", 1f);
            return material;
        }

        private void texture(Material material, String parameter) throws IOException {
            int index = input.readInt();
            if (index < 0) {
                return;
            }
            SourceTexture image = images[index];
            Texture texture;
            if (image.data().length != 0) {
                Image pixels = new AWTLoader().load(new ByteArrayInputStream(image.data()), true);
                if (pixels == null) {
                    throw new IOException("Unsupported embedded FBX texture " + image.path());
                }
                TextureKey key = new TextureKey("ufbx-image" + index + ".png", true);
                key.setGenerateMips(true);
                texture = new Texture2D(pixels);
                texture.setKey(key);
                texture.setMinFilter(Texture.MinFilter.Trilinear);
            } else {
                texture = externalTexture(image.path());
                String keyName = texture.getKey().getName().toLowerCase(Locale.ROOT);
                if (!keyName.endsWith(".png") && !keyName.endsWith(".jpg")
                        && !keyName.endsWith(".jpeg") && !keyName.endsWith(".dds")) {
                    texture = texture.clone();
                    TextureKey key = new TextureKey("ufbx-image" + index + ".png", true);
                    key.setGenerateMips(true);
                    texture.setKey(key);
                }
            }
            texture.setWrap(Texture.WrapMode.Repeat);
            material.setTexture(parameter, texture);
        }

        private Texture externalTexture(String sourcePath) throws IOException {
            String path = sourcePath.replace('\\', '/');
            String name = path.substring(path.lastIndexOf('/') + 1);
            String folder = info.getKey().getFolder();
            if (name.isBlank()) {
                throw new IOException("FBX material is missing its texture filename");
            }
            if (!path.startsWith("/") && path.indexOf(':') < 0) {
                Path relative = Path.of(folder + path).normalize();
                if (!relative.startsWith("..")) {
                    try {
                        return loadTexture(relative.toString().replace('\\', '/'));
                    } catch (AssetNotFoundException ex) {
                        // FBX exporters frequently leave paths from a different project.
                    }
                }
            }
            // Unity packages often retain an FBX path relative to an ancestor
            // of the model, for example "FPS Framework/Art/Textures/...".
            // Resolve only inside the asset root, never against an OS path.
            if (path.contains("/") && !path.startsWith("/") && path.indexOf(':') < 0
                    && !java.util.Arrays.asList(path.split("/")).contains("..")) {
                String ancestor = folder;
                while (!ancestor.isEmpty()) {
                    int slash = ancestor.lastIndexOf('/', ancestor.length() - 2);
                    ancestor = slash < 0 ? "" : ancestor.substring(0, slash + 1);
                    try {
                        return loadTexture(ancestor + path);
                    } catch (AssetNotFoundException ex) {
                        // Continue toward the asset root.
                    }
                }
            }
            try {
                return loadTexture(folder + name);
            } catch (AssetNotFoundException ex) {
                throw new IOException("Missing FBX texture " + sourcePath, ex);
            }
        }

        private Texture loadTexture(String name) {
            TextureKey key = new TextureKey(name, true);
            key.setGenerateMips(true);
            return info.getManager().loadTexture(key);
        }

        private void mesh(Node root) throws IOException {
            int nodeId = input.readInt();
            Node source = nodes[nodeId];
            int bindings = count(52);
            Node target = source;
            int[] boneIndices = new int[bindings];
            if (bindings > 0) {
                target = new Node(source.getName() + " skin");
                root.attachChild(target);
                Joint[] sourceJoints = new Joint[nodes.length];
                List<Joint> joints = new ArrayList<>();
                for (int i = 0; i < bindings; ++i) {
                    int bone = input.readInt();
                    Joint parent = joint(bone, sourceJoints, joints);
                    Joint binding = new Joint(parent.getName() + " binding " + i);
                    parent.addChild(binding);
                    binding.setInverseModelBindMatrix(matrix());
                    boneIndices[i] = joints.size();
                    joints.add(binding);
                }
                if (joints.size() > 32767) {
                    throw new IOException("FBX skin has too many joints");
                }
                Armature armature = new Armature(joints.toArray(Joint[]::new));
                armature.saveInitialPose();
                SkinningControl skinning = new SkinningControl(armature);
                rigs.add(sourceJoints);
                target.addControl(skinning);
            }
            int parts = count(8);
            for (int i = 0; i < parts; ++i) {
                int materialId = input.readInt();
                int vertices = count(bindings == 0 ? 32 : 64);
                if (vertices != 0) {
                    Material material = materialId < 0 ? defaultMaterial() : materials[materialId];
                    Geometry geometry = new Geometry(source.getName() + " " + i, geometry(vertices, boneIndices, material));
                    geometry.setMaterial(material);
                    if (material.isTransparent()) {
                        geometry.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
                    }
                    target.attachChild(geometry);
                }
            }
        }

        private Joint joint(int index, Joint[] known, List<Joint> joints) {
            if (known[index] != null) {
                return known[index];
            }
            Node node = nodes[index];
            Joint result = new Joint(node.getName());
            result.setLocalTransform(node.getLocalTransform().clone());
            int parent = parents[index];
            if (parent >= 0) {
                joint(parent, known, joints).addChild(result);
            }
            joints.add(result);
            known[index] = result;
            return result;
        }

        private Mesh geometry(int vertices, int[] boneIndices, Material material) throws IOException {
            var positions = BufferUtils.createFloatBuffer(vertices * 3);
            var normals = BufferUtils.createFloatBuffer(vertices * 3);
            var uv = BufferUtils.createFloatBuffer(vertices * 2);
            var indices = BufferUtils.createIntBuffer(vertices);
            boolean skinned = boneIndices.length != 0;
            var bones = skinned ? BufferUtils.createShortBuffer(vertices * 4) : null;
            var weights = skinned ? BufferUtils.createFloatBuffer(vertices * 4) : null;
            for (int i = 0; i < vertices; ++i) {
                for (int component = 0; component < 3; ++component) {
                    positions.put(input.readFloat());
                }
                for (int component = 0; component < 3; ++component) {
                    normals.put(input.readFloat());
                }
                uv.put(input.readFloat()).put(input.readFloat());
                indices.put(i);
                if (skinned) {
                    for (int weight = 0; weight < 4; ++weight) {
                        bones.put((short) boneIndices[input.readInt()]);
                        weights.put(input.readFloat());
                    }
                }
            }
            Mesh mesh = new Mesh();
            mesh.setBuffer(VertexBuffer.Type.Position, 3, positions.flip());
            mesh.setBuffer(VertexBuffer.Type.Normal, 3, normals.flip());
            mesh.setBuffer(VertexBuffer.Type.TexCoord, 2, uv.flip());
            mesh.setBuffer(VertexBuffer.Type.Index, 3, indices.flip());
            if (material.getTextureParam("NormalMap") != null) {
                MikktspaceTangentGenerator.generate(mesh);
            }
            if (skinned) {
                mesh.setBuffer(VertexBuffer.Type.BoneIndex, 4, VertexBuffer.Format.UnsignedShort, bones.flip());
                mesh.setBuffer(VertexBuffer.Type.BoneWeight, 4, weights.flip());
                mesh.setMaxNumWeights(4);
                mesh.generateBindPose();
            }
            mesh.updateBound();
            return mesh;
        }

        private void animations(Node root) throws IOException {
            int clips = count(12);
            if (clips == 0) {
                return;
            }
            AnimComposer composer = new AnimComposer();
            for (int i = 0; i < clips; ++i) {
                String name = string();
                if (name.isBlank()) {
                    name = "Take " + (i + 1);
                }
                String original = name;
                for (int suffix = 2; composer.hasAnimClip(name); ++suffix) {
                    name = original + " (" + suffix + ")";
                }
                int frames = count(4);
                float duration = input.readFloat();
                if (frames < 1 || (long) frames * nodes.length * 40 > input.available()) {
                    throw new IOException("Invalid FBX animation sample count");
                }
                float[] times = new float[frames];
                for (int frame = 1; frame < frames; ++frame) {
                    times[frame] = (float) ((double) duration * frame / (frames - 1));
                }
                List<AnimTrack<?>> tracks = new ArrayList<>();
                for (int node = 0; node < nodes.length; ++node) {
                    Vector3f[] positions = new Vector3f[frames];
                    Quaternion[] rotations = new Quaternion[frames];
                    Vector3f[] scales = new Vector3f[frames];
                    for (int frame = 0; frame < frames; ++frame) {
                        positions[frame] = vector();
                        rotations[frame] = quaternion();
                        scales[frame] = vector();
                    }
                    track(tracks, nodes[node], times, positions, rotations, scales);
                    for (Joint[] rig : rigs) {
                        if (rig[node] != null) {
                            track(tracks, rig[node], times, positions, rotations, scales);
                        }
                    }
                }
                AnimClip clip = new AnimClip(name);
                clip.setTracks(tracks.toArray(AnimTrack[]::new));
                composer.addAnimClip(clip);
            }
            root.addControl(composer);
        }

        private static void track(List<AnimTrack<?>> tracks, HasLocalTransform target, float[] times,
                Vector3f[] positions, Quaternion[] rotations, Vector3f[] scales) {
            tracks.add(new TransformTrack(target, times, positions, rotations, scales));
        }

        private Matrix4f matrix() throws IOException {
            Matrix4f matrix = new Matrix4f();
            for (int column = 0; column < 4; ++column) {
                for (int row = 0; row < 3; ++row) {
                    matrix.set(row, column, input.readFloat());
                }
            }
            return matrix;
        }

        private Transform transform() throws IOException {
            return new Transform(vector(), quaternion(), vector());
        }

        private Vector3f vector() throws IOException {
            return new Vector3f(input.readFloat(), input.readFloat(), input.readFloat());
        }

        private Quaternion quaternion() throws IOException {
            return new Quaternion(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat());
        }

        private int count(int minimumBytesPerEntry) throws IOException {
            int count = input.readInt();
            if (count < 0 || count > input.available() / minimumBytesPerEntry) {
                throw new IOException("Invalid ufbx scene count");
            }
            return count;
        }

        private byte[] bytes() throws IOException {
            return input.readNBytes(count(1));
        }

        private String string() throws IOException {
            return new String(bytes(), StandardCharsets.UTF_8);
        }
    }

    private record SourceTexture(String path, byte[] data) {
    }
}

package com.jme3.gde.core.assets;

import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.mesh.IndexBuffer;
import com.jme3.util.BufferUtils;
import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Locale;
import org.openide.modules.InstalledFileLocator;

/**
 * xatlas integration for generating a nonoverlapping second UV channel.
 * Result meshes own their buffers. Prefer generating before producing LODs, since
 * chart seams can introduce additional vertices.
 * <pre>{@code
 * // 第一步：转换模型时，对刚体网格勾选 Generate lightmap UV2。
 * Mesh lightmapMesh = LightmapUvGenerator.generate(mesh);
 * // 第二步：使用返回的网格，再生成需要的 LOD 并保存 J3O。
 * geometry.setMesh(lightmapMesh);
 * }</pre>
 * Existing UV2 is returned unchanged. Conversion leaves skinned meshes unchecked by default.
 */
public final class LightmapUvGenerator {
    private static boolean loaded;

    private LightmapUvGenerator() {
    }

    public static Mesh generate(Mesh source) throws IOException {
        if (source.getBuffer(Type.TexCoord2) != null) {
            return source;
        }
        if (source.isAnimated() || source.getMorphTargets().length != 0
                || source.getMode() != Mesh.Mode.Triangles || source.getTriangleCount() == 0) {
            throw new IOException("Lightmap UVs require a rigid triangle mesh");
        }
        VertexBuffer position = source.getBuffer(Type.Position);
        if (position == null || position.getNumComponents() != 3 || position.getStride() != 0
                || !(position.getDataReadOnly() instanceof FloatBuffer positions)) {
            throw new IOException("Lightmap UVs require noninterleaved float positions");
        }
        float[] xyz = new float[Math.multiplyExact(3, source.getVertexCount())];
        positions.get(xyz);
        IndexBuffer triangles = source.getIndicesAsList();
        int[] indices = new int[triangles.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = triangles.get(i);
        }
        loadLibrary();
        int[] packet = unwrap(xyz, indices);
        if (packet == null || packet.length < 1 || packet[0] < 1
                || 1L + packet[0] * 3L + indices.length != packet.length) {
            throw new IOException("xatlas produced an invalid lightmap layout");
        }
        int vertices = packet[0];
        float[] uv = new float[vertices * 2];
        Mesh result = source.clone();
        for (VertexBuffer buffer : source.getBufferList()) {
            if (buffer.getBufferType() == Type.Index) {
                continue;
            }
            if (buffer.getData() == null || buffer.getStride() != 0 || buffer.isInstanced()
                    || buffer.getNumElements() != source.getVertexCount()) {
                throw new IOException("Cannot remap vertex buffer " + buffer.getBufferType());
            }
            VertexBuffer output = new VertexBuffer(buffer.getBufferType());
            output.setupData(buffer.getUsage(), buffer.getNumComponents(), buffer.getFormat(),
                    VertexBuffer.createBuffer(buffer.getFormat(), buffer.getNumComponents(), vertices));
            output.setNormalized(buffer.isNormalized());
            for (int i = 0; i < vertices; i++) {
                buffer.copyElements(packet[1 + i * 3], output, i, 1);
            }
            result.clearBuffer(buffer.getBufferType());
            result.setBuffer(output);
        }
        for (int i = 0; i < vertices; i++) {
            uv[i * 2] = Float.intBitsToFloat(packet[2 + i * 3]);
            uv[i * 2 + 1] = Float.intBitsToFloat(packet[3 + i * 3]);
        }
        System.arraycopy(packet, 1 + vertices * 3, indices, 0, indices.length);
        result.setLodLevels(remapLods(source, packet, vertices));
        result.clearBuffer(Type.Index);
        result.setBuffer(Type.Index, 3, indices);
        result.setBuffer(Type.TexCoord2, 2, uv);
        result.updateCounts();
        result.updateBound();
        result.clearCollisionData();
        return result;
    }

    private static VertexBuffer[] remapLods(Mesh source, int[] packet, int vertices) throws IOException {
        int count = source.getNumLodLevels();
        if (count == 0) {
            return null;
        }
        int[] representative = new int[source.getVertexCount()];
        Arrays.fill(representative, -1);
        for (int i = vertices - 1; i >= 0; i--) {
            representative[packet[1 + i * 3]] = i;
        }
        VertexBuffer[] levels = new VertexBuffer[count];
        IndexBuffer full = source.getIndicesAsList();
        for (int i = 0; i < count; i++) {
            VertexBuffer original = source.getLodLevel(i);
            IndexBuffer input = IndexBuffer.wrapIndexBuffer(original.getDataReadOnly());
            var indices = BufferUtils.createIntBuffer(input.size());
            VertexBuffer level = new VertexBuffer(Type.Index);
            if (i == 0 && sameIndices(full, input)) {
                // Full-detail corners must point to the correct chart at a seam.
                indices.put(packet, 1 + vertices * 3, input.size());
            } else {
                for (int j = 0; j < input.size(); j++) {
                    int index = representative[input.get(j)];
                    if (index < 0) {
                        throw new IOException("Atlas omits a vertex required by LOD " + i);
                    }
                    indices.put(index);
                }
            }
            indices.flip();
            level.setupData(original.getUsage(), 3, VertexBuffer.Format.UnsignedInt, indices);
            levels[i] = level;
        }
        return levels;
    }

    private static boolean sameIndices(IndexBuffer first, IndexBuffer second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int i = 0; i < first.size(); i++) {
            if (first.get(i) != second.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static synchronized void loadLibrary() throws IOException {
        if (loaded) {
            return;
        }
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        os = os.startsWith("windows") ? "windows" : os.startsWith("mac") ? "macos" : os;
        arch = "amd64".equals(arch) ? "x86_64" : "aarch64".equals(arch) ? "arm64" : arch;
        String target = os + "-" + arch;
        String override = System.getProperty("jme.sdk.xatlas.library");
        File library = override == null ? InstalledFileLocator.getDefault().locate(
                "lib/xatlas/" + target + "/" + System.mapLibraryName("jme-xatlas"),
                "com.jme3.gde.core", false) : new File(override);
        if (library == null || !library.isFile()) {
            throw new IOException("The SDK xatlas library is missing for " + target);
        }
        try {
            System.load(library.getCanonicalPath());
            loaded = true;
        } catch (LinkageError | SecurityException failure) {
            throw new IOException("Unable to load xatlas from " + library, failure);
        }
    }

    private static native int[] unwrap(float[] xyz, int[] indices) throws IOException;
}

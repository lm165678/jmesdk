package com.jme3.gde.scenecomposer;

import com.jme3.export.binary.BinaryExporter;
import com.jme3.math.Vector3f;
import com.jme3.scene.*;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.util.BufferUtils;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.*;
import jme3tools.optimize.LodGenerator;

/** CPU model operations on isolated mesh copies; no live geometry is changed. */
final class ModelBakeSupport {
    /** Reject editor gizmos, detached data and non-model nodes before any mesh operation. */
    static boolean isEditableSceneModel(Spatial target) {
        var request = com.jme3.gde.core.scene.SceneApplication.getApplication().getCurrentSceneRequest();
        if (target == null || target instanceof com.jme3.audio.AudioNode
                || target instanceof com.jme3.environment.gi.GiProbeVolume
                || request == null || request.getJmeNode() == null || request.getJmeNode().isReadOnly()) {
            return false;
        }
        for (Spatial ancestor = target; ancestor != null; ancestor = ancestor.getParent()) {
            if (ancestor == request.getRootNode()) {
                return !geometries(target).isEmpty();
            }
        }
        return false;
    }

    static List<Geometry> geometries(Spatial root) {
        List<Geometry> result = new ArrayList<>();
        root.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry geometry && geometry.getMesh() != null && geometry.getMesh().getTriangleCount() > 0) {
                Mesh.Mode mode = geometry.getMesh().getMode();
                if (mode == Mesh.Mode.Triangles || mode == Mesh.Mode.TriangleStrip || mode == Mesh.Mode.TriangleFan) {
                    result.add(geometry);
                }
            }
        });
        return result;
    }

    static Spatial copy(Spatial source) {
        Spatial copy;
        if (source instanceof Geometry geometry) {
            Geometry clone = new Geometry(source.getName(), geometry.getMesh().deepClone());
            if (geometry.getMaterial() != null) {
                clone.setMaterial(geometry.getMaterial().clone());
            }
            copy = clone;
        } else {
            Node node = new Node(source.getName());
            if (source instanceof Node original) {
                for (Spatial child : original.getChildren()) {
                    node.attachChild(copy(child));
                }
            }
            copy = node;
        }
        copy.setLocalTransform(source.getLocalTransform().clone());
        return copy;
    }

    static byte[] fingerprint(Spatial source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestOutputStream output = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
            for (Geometry geometry : geometries(source)) {
                BinaryExporter.getInstance().save(geometry.getMesh(), output);
                output.write(geometry.getWorldTransform().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return digest.digest();
    }

    static void lod(Spatial copy, float[] retained) {
        float[] removed = retained.clone();
        float previous = 1;
        for (int i = 0; i < removed.length; i++) {
            if (!Float.isFinite(retained[i]) || retained[i] <= 0 || retained[i] >= previous) {
                throw new IllegalArgumentException("LOD retained ratios must decrease between 0 and 1, e.g. 0.5,0.25,0.125");
            }
            previous = retained[i];
            removed[i] = 1 - retained[i];
        }
        for (Geometry geometry : geometries(copy)) {
            new LodGenerator(geometry).bakeLods(LodGenerator.TriangleReductionMethod.PROPORTIONAL, removed);
        }
    }

    static void normalsAndTangents(Spatial copy, boolean normals, boolean tangents) {
        for (Geometry geometry : geometries(copy)) {
            Mesh mesh = geometry.getMesh();
            if (normals) {
                Vector3f[] sums = new Vector3f[mesh.getVertexCount()];
                Arrays.setAll(sums, index -> new Vector3f());
                int[] indices = new int[3];
                Vector3f a = new Vector3f();
                Vector3f b = new Vector3f();
                Vector3f c = new Vector3f();
                for (int i = 0; i < mesh.getTriangleCount(); i++) {
                    mesh.getTriangle(i, indices);
                    mesh.getTriangle(i, a, b, c);
                    Vector3f normal = b.subtract(a).cross(c.subtract(a));
                    for (int index : indices) {
                        sums[index].addLocal(normal);
                    }
                }
                for (Vector3f normal : sums) {
                    normal.normalizeLocal();
                }
                mesh.setBuffer(Type.Normal, 3, BufferUtils.createFloatBuffer(sums));
                mesh.clearBuffer(Type.Tangent);
                mesh.clearBuffer(Type.Binormal);
            }
            if (tangents) {
                if (mesh.getBuffer(Type.Normal) == null || mesh.getBuffer(Type.TexCoord) == null) {
                    throw new IllegalArgumentException("Tangents require normals and UV0: " + geometry.getName());
                }
                MikktspaceTangentGenerator.generate(mesh);
                if (mesh.getBuffer(Type.Tangent) == null) {
                    throw new IllegalStateException("Tangent generation failed: " + geometry.getName());
                }
            }
        }
    }
}

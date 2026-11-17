package com.jme3.gde.unityscene;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/** GUID-to-project-asset index built from Unity .meta sidecars. */
final class UnityAssetIndex {
    private static final Pattern GUID = Pattern.compile(
            "(?m)^guid:\\s*([0-9a-fA-F]{32})\\s*$");
    private final FileObject root;
    private final Map<String, FileObject> byGuid = new HashMap<>();
    private final Map<String, Map<Long, String>> meshNames = new HashMap<>();

    UnityAssetIndex(FileObject root) throws IOException {
        this(root, null);
    }

    /** Prefer sidecars from the current import when another package repeats a GUID. */
    UnityAssetIndex(FileObject root, FileObject preferred) throws IOException {
        this.root = root;
        Enumeration<? extends FileObject> children = root.getChildren(true);
        while (children.hasMoreElements()) {
            FileObject meta = children.nextElement();
            if (!meta.isData() || !"meta".equalsIgnoreCase(meta.getExt())) {
                continue;
            }
            Matcher matcher = GUID.matcher(meta.asText("UTF-8"));
            if (!matcher.find()) {
                continue;
            }
            String relative = FileUtil.getRelativePath(root, meta);
            if (relative == null) {
                continue;
            }
            FileObject asset = root.getFileObject(relative.substring(0, relative.length() - 5));
            if (asset != null) {
                String guid = matcher.group(1).toLowerCase(Locale.ROOT);
                FileObject existing = byGuid.get(guid);
                if (existing == null || preferred == null || FileUtil.isParentOf(preferred, asset)
                        || !FileUtil.isParentOf(preferred, existing)) {
                    byGuid.put(guid, asset);
                }
            }
        }
    }

    FileObject find(UnityYaml.Ref reference) {
        if (reference == null || reference.guid() == null) {
            return null;
        }
        return byGuid.get(reference.guid().toLowerCase(Locale.ROOT));
    }

    String path(FileObject asset) {
        String relative = FileUtil.getRelativePath(root, asset);
        return relative == null ? null : relative.replace('\\', '/');
    }

    /**
     * Resolves an imported Mesh fileID through ModelImporter's old or current
     * fileID-to-name table. Null means the caller should use the whole model.
     */
    String meshName(FileObject model, long fileId) throws IOException {
        String path = path(model);
        if (path == null) {
            return null;
        }
        Map<Long, String> names = meshNames.get(path);
        if (names == null) {
            names = readMeshNames(root.getFileObject(path + ".meta"));
            meshNames.put(path, names);
        }
        return names.get(fileId);
    }

    private Map<Long, String> readMeshNames(FileObject meta) throws IOException {
        Map<Long, String> result = new HashMap<>();
        if (meta == null) {
            return result;
        }
        Map<String, Object> rootMap = UnityYaml.map(UnityYaml.load(meta.asText("UTF-8")));
        Map<String, Object> importer = UnityYaml.map(rootMap.get("ModelImporter"));
        // YAML numeric file IDs are Number keys, not necessarily Strings.
        for (Map.Entry<?, ?> entry : UnityYaml.map(
                importer.get("fileIDToRecycleName")).entrySet()) {
            long id = UnityYaml.longValue(entry.getKey(), 0);
            if (id != 0) {
                result.put(id, String.valueOf(entry.getValue()));
            }
        }
        for (Object item : UnityYaml.list(importer.get("internalIDToNameTable"))) {
            Map<String, Object> entry = UnityYaml.map(item);
            Map<String, Object> first = UnityYaml.map(entry.get("first"));
            for (Map.Entry<?, ?> classAndId : first.entrySet()) {
                long classId = UnityYaml.longValue(classAndId.getKey(), 0);
                long id = UnityYaml.longValue(classAndId.getValue(), 0);
                if (classId == 43 && id != 0) {
                    result.put(id, UnityYaml.stringValue(entry.get("second"), null));
                }
            }
        }
        return result;
    }
}

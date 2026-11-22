package com.jme3.gde.unityscene;

import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/** Minimal Unity YAML document reader; Unity class IDs are kept separately from YAML tags. */
final class UnityYaml {
    private static final Pattern DOCUMENT = Pattern.compile(
            "^---\\s+!u!(\\d+)\\s+&(-?\\d+)(\\s+stripped)?\\s*$");
    private static final LoadSettings SETTINGS = LoadSettings.builder()
            .setAllowDuplicateKeys(true)
            .build();

    private UnityYaml() {
    }

    record Document(int classId, long fileId, boolean stripped, String type,
            Map<String, Object> values) {
    }

    record Ref(long fileId, String guid, int type) {
        boolean isZero() {
            return fileId == 0 && (guid == null || guid.isBlank());
        }
    }

    /**
     * Reads Unity's multi-document YAML after stripping the !u! document tag.
     * The body stays ordinary YAML, including inline fileID references.
     */
    static List<Document> parse(String text) throws IOException {
        if (text.indexOf('\0') >= 0) {
            throw new IOException("Binary Unity serialization is not supported. Import the package into Unity, "
                    + "set Asset Serialization to Force Text, then reserialize the scenes, prefabs and materials. "
                    + "Copy the resulting assets together with their .meta files and retry conversion. "
                    + "Changing the setting alone does not rewrite existing binary assets.");
        }
        List<Document> result = new ArrayList<>();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int classId = -1;
        long fileId = 0;
        boolean stripped = false;
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            Matcher header = DOCUMENT.matcher(line);
            if (header.matches()) {
                if (classId >= 0) {
                    result.add(document(classId, fileId, stripped, body.toString()));
                }
                classId = Integer.parseInt(header.group(1));
                fileId = Long.parseLong(header.group(2));
                stripped = header.group(3) != null;
                body.setLength(0);
            } else if (classId >= 0) {
                body.append(line).append('\n');
            }
        }
        if (classId >= 0) {
            result.add(document(classId, fileId, stripped, body.toString()));
        }
        if (result.isEmpty()) {
            throw new IOException("No Unity YAML document headers found. Expected a text-serialized .unity/.prefab asset.");
        }
        return result;
    }

    private static Document document(int classId, long fileId, boolean stripped,
            String body) throws IOException {
        Object loaded = load(body);
        Map<String, Object> root = map(loaded);
        if (root.isEmpty()) {
            throw new IOException("Empty Unity YAML document &" + fileId);
        }
        Map.Entry<String, Object> first = root.entrySet().iterator().next();
        return new Document(classId, fileId, stripped, first.getKey(), map(first.getValue()));
    }

    /** Reads a single YAML document, such as a Unity .meta file. */
    static Object load(String text) throws IOException {
        try {
            // Each concurrent asset load needs its own parser/construction state.
            Object loaded = new Load(SETTINGS).loadFromString(text);
            return loaded == null ? Collections.emptyMap() : loaded;
        } catch (RuntimeException failure) {
            throw new IOException("Invalid Unity YAML: " + failure.getMessage(), failure);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return Collections.emptyList();
    }

    static Ref ref(Object value) {
        Map<String, Object> map = map(value);
        String guid = stringValue(map.get("guid"), null);
        return new Ref(longValue(map.get("fileID"), 0), guid,
                (int) longValue(map.get("type"), 0));
    }

    static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    static float floatValue(Object value, float fallback) {
        if (value instanceof Number number) {
            float result = number.floatValue();
            return Float.isFinite(result) ? result : fallback;
        }
        if (value instanceof String text) {
            try {
                float result = Float.parseFloat(text.trim());
                return Float.isFinite(result) ? result : fallback;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            return "true".equalsIgnoreCase(text) || "1".equals(text);
        }
        return fallback;
    }

    static String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    static Vector3f vector3(Object value, Vector3f fallback) {
        Map<String, Object> map = map(value);
        if (map.isEmpty()) {
            return fallback.clone();
        }
        return new Vector3f(floatValue(map.get("x"), fallback.x),
                floatValue(map.get("y"), fallback.y),
                floatValue(map.get("z"), fallback.z));
    }

    static Quaternion quaternion(Object value) {
        Map<String, Object> map = map(value);
        return new Quaternion(floatValue(map.get("x"), 0),
                floatValue(map.get("y"), 0), floatValue(map.get("z"), 0),
                floatValue(map.get("w"), 1));
    }

    static ColorRGBA color(Object value, ColorRGBA fallback) {
        Map<String, Object> map = map(value);
        if (map.isEmpty()) {
            return fallback.clone();
        }
        return new ColorRGBA(floatValue(map.get("r"), fallback.r),
                floatValue(map.get("g"), fallback.g), floatValue(map.get("b"), fallback.b),
                floatValue(map.get("a"), fallback.a));
    }

    /** Reads modern lists of single-entry maps and legacy direct maps. */
    static List<Map.Entry<String, Object>> properties(Object value) {
        List<Map.Entry<String, Object>> result = new ArrayList<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.add(new AbstractMap.SimpleImmutableEntry<>(
                        String.valueOf(entry.getKey()), entry.getValue()));
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object first = map.get("first");
                    if (first != null && map.containsKey("second")) {
                        String name = UnityYaml.stringValue(map(first).get("name"), null);
                        if (name != null) {
                            result.add(new AbstractMap.SimpleImmutableEntry<>(
                                    name, map.get("second")));
                            continue;
                        }
                    }
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        result.add(new AbstractMap.SimpleImmutableEntry<>(
                                String.valueOf(entry.getKey()), entry.getValue()));
                    }
                }
            }
        }
        return result;
    }
}

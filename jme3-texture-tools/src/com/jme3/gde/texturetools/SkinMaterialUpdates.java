package com.jme3.gde.texturetools;

import com.jme3.material.MatParam;
import com.jme3.material.Material;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;

/**
 * Publishes committed skin parameters to open SDK editors on EDT. File identity
 * includes the project path; equal asset keys in different projects are distinct.
 * Register when an editor opens and remove its listener when it closes.
 *
 * <pre>{@code
 * // 第一步：编辑器打开时注册，关闭时移除同一个 listener。
 * SkinMaterialUpdates.addListener(listener);
 * // 第二步：导出事务成功后，在 EDT 发布已保存的材质文本。
 * SkinMaterialUpdates.publish(materialPath, savedText);
 * // 第三步：仅合并本次生成的参数，保留其他未保存的编辑。
 * String merged = SkinMaterialUpdates.merge(editorText, savedText);
 * }</pre>
 * Publication never saves a scene or starts the renderer. Scene updates must be
 * enqueued by the SDK bridge; a failed export must not publish an update.
 */
public final class SkinMaterialUpdates {
    public static final Set<String> SURFACE_PARAMETERS = Set.of("SkinThicknessMap",
            "SkinCurvatureMap", "SkinThickness", "SkinWorldToMillimeters", "SkinTransmission");
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private SkinMaterialUpdates() {
    }

    /** Receives a normalized absolute path and committed UTF-8 source on EDT. */
    public interface Listener {
        void exported(Path material, String savedText) throws IOException;
    }

    public static void addListener(Listener listener) {
        LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static void publish(Path material, String savedText) throws IOException {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Publish skin material updates on EDT");
        }
        Path absolute = material.toAbsolutePath().normalize();
        for (Listener listener : LISTENERS) {
            listener.exported(absolute, savedText);
        }
    }

    /** Replaces only surface-bake parameters, retaining other editor text. */
    public static String merge(String editorText, String savedText) throws IOException {
        return SkinLutExport.mergeParameters(editorText, savedText, SURFACE_PARAMETERS);
    }

    /** Applies baked fields in place on the render thread, retaining runtime overrides. */
    public static void apply(Material target, Material saved) {
        for (String name : SURFACE_PARAMETERS) {
            MatParam parameter = saved.getParam(name);
            if (parameter != null) {
                target.setParam(name, parameter.getVarType(), parameter.getValue());
            }
        }
    }
}

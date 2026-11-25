package com.jme3.gde.core.scene;

import com.jme3.post.Filter;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.UpscalingStage;
import com.jme3.post.filters.TemporalAAFilter;
import com.jme3.renderer.ViewPort;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Render-thread suppression of preview reconstruction during an offline bake.
 * Includes disabled stages so user edits cannot enable them during capture.
 * <pre>{@code
 * // 第一步：任务实际开始时创建；排队期间不改变预览。
 * GiBakeReconstructionGuard guard = new GiBakeReconstructionGuard(viewPort);
 * // 第二步：refresh 返回 true 时等待一个正常预览帧恢复原生尺寸，再采样。
 * if (!guard.refresh()) {
 *     job.step(budget);
 * }
 * // 第三步：Probe 和 Lightmap 完成、取消、失败或关闭场景时执行。
 * guard.close();
 * // 用户的启用偏好始终正常序列化，临时挂起不会保存到 J3O。
 * }</pre>
 */
public final class GiBakeReconstructionGuard implements AutoCloseable {
    private final ViewPort view;
    private final Map<Filter, Filter.CaptureSuspension> scopes = new IdentityHashMap<>();
    private boolean closed;

    public GiBakeReconstructionGuard(ViewPort view) {
        this.view = java.util.Objects.requireNonNull(view);
    }

    /** Returns true when a newly found stage needs one frame to settle. */
    public boolean refresh() {
        if (closed) {
            return false;
        }
        boolean added = false;
        for (var processor : view.getProcessors().getArray()) {
            if (processor instanceof FilterPostProcessor chain) {
                for (Filter filter : chain.getFilterList()) {
                    if ((filter instanceof UpscalingStage || filter instanceof TemporalAAFilter)
                            && !scopes.containsKey(filter)) {
                        scopes.put(filter, filter.suspendForCapture());
                        added = true;
                    }
                }
            }
        }
        return added;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            for (var scope : scopes.values()) {
                scope.close();
            }
        } finally {
            scopes.clear();
        }
    }
}

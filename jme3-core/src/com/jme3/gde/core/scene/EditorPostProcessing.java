package com.jme3.gde.core.scene;

import com.jme3.post.Filter;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.*;
import com.jme3.post.ssao.SSAOFilter;
import com.jme3.math.Vector3f;
import com.jme3.texture.Image;
import com.jme3.renderer.Caps;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Preview-only HDR chain, owned and mutated by the render thread. Tone mapping
 * and FXAA start enabled; optional effects start disabled. A scene-owned FPP
 * or non-Lit diagnostic view bypasses this chain to avoid processing twice.
 * RGBA16F is preferred, RGBA32F is the fallback; unsupported GPUs bypass it.
 * This processor never attaches to GI capture viewports or modifies scene assets.
 * Manual exposure defaults to 0 EV, after scene effects and before Bloom/ToneMap.
 * <pre>
 * 第一步：展开 Scene Details 的 Post processing，默认启用 HDR、ToneMap 和 FXAA。
 * 第二步：勾选所需效果，展开各效果子分组，调整参数；关闭效果会保留其参数。
 * 第三步：关闭 Global HDR / FPP 进行原始结果对照，再开启恢复配置。
 * 第四步：展开 Exposure，调节 Compensation (EV)；+1 加倍、-1 减半，0 恢复。
 * 曝光仅影响预览，随此处理器的旁路规则关闭；它不替代 Bloom 的提取参数。
 * </pre>
 */
final class EditorPostProcessing {
    private final SceneApplication app;
    private final Map<String, Filter> filters = new LinkedHashMap<>();
    private FilterPostProcessor processor;
    private boolean enabled = true;
    private boolean lit = true;
    private boolean supported = true;

    EditorPostProcessing(SceneApplication app) {
        this.app = app;
    }

    private void create() {
        var caps = app.getRenderer().getCaps();
        supported = caps.contains(Caps.HalfFloatColorBufferRGBA)
                || caps.contains(Caps.FloatColorBufferRGBA);
        if (!supported) {
            return;
        }
        processor = new FilterPostProcessor(app.getAssetManager());
        processor.setFrameBufferFormat(caps.contains(Caps.HalfFloatColorBufferRGBA)
                ? Image.Format.RGBA16F : Image.Format.RGBA32F);
        add("SSAO", new SSAOFilter(), false);
        add("Fog", new FogFilter(), false);
        add("DepthOfField", new DepthOfFieldFilter(), false);
        add("Exposure", new ExposureFilter(), true);
        add("Bloom", new BloomFilter(BloomFilter.GlowMode.Scene), false);
        add("ToneMap", new ToneMapFilter(), true);
        add("FXAA", new FXAAFilter(), true);
    }

    private void add(String key, Filter filter, boolean active) {
        filter.setEnabled(active);
        filters.put(key, filter);
        processor.addFilter(filter);
    }

    void configure(String option, double value) {
        if (processor == null && supported) {
            create();
        }
        if (option.equals("Enabled")) {
            enabled = value != 0;
        } else if (option.equals("Lit")) {
            lit = value != 0;
        } else if (processor != null && option.equals("ExposureCompensation")) {
            ((ExposureFilter) filters.get("Exposure")).setExposureCompensation((float) value);
        } else if (processor != null && option.equals("BloomIntensity")) {
            ((BloomFilter) filters.get("Bloom")).setBloomIntensity((float) value);
        } else if (processor != null && option.equals("WhitePoint")) {
            ((ToneMapFilter) filters.get("ToneMap")).setWhitePoint(new Vector3f((float) value, (float) value, (float) value));
        } else if (processor != null && option.equals("BloomBlurScale")) {
            ((BloomFilter) filters.get("Bloom")).setBlurScale((float) value);
        } else if (processor != null && option.equals("BloomExposurePower")) {
            ((BloomFilter) filters.get("Bloom")).setExposurePower((float) value);
        } else if (processor != null && option.equals("BloomExposureCutOff")) {
            ((BloomFilter) filters.get("Bloom")).setExposureCutOff((float) value);
        } else if (processor != null && option.equals("BloomDownSampling")) {
            ((BloomFilter) filters.get("Bloom")).setDownSamplingFactor((float) value);
        } else if (processor != null && option.equals("FXAASubPixelShift")) {
            ((FXAAFilter) filters.get("FXAA")).setSubPixelShift((float) value);
        } else if (processor != null && option.equals("FXAASpanMax")) {
            ((FXAAFilter) filters.get("FXAA")).setSpanMax((float) value);
        } else if (processor != null && option.equals("FXAAReduceMul")) {
            ((FXAAFilter) filters.get("FXAA")).setReduceMul((float) value);
        } else if (processor != null && option.equals("FXAAVxOffset")) {
            ((FXAAFilter) filters.get("FXAA")).setVxOffset((float) value);
        } else if (processor != null && option.equals("SSAORadius")) {
            ((SSAOFilter) filters.get("SSAO")).setSampleRadius((float) value);
        } else if (processor != null && option.equals("SSAOIntensity")) {
            ((SSAOFilter) filters.get("SSAO")).setIntensity((float) value);
        } else if (processor != null && option.equals("SSAOScale")) {
            ((SSAOFilter) filters.get("SSAO")).setScale((float) value);
        } else if (processor != null && option.equals("SSAOBias")) {
            ((SSAOFilter) filters.get("SSAO")).setBias((float) value);
        } else if (processor != null && option.equals("FogDensity")) {
            ((FogFilter) filters.get("Fog")).setFogDensity((float) value);
        } else if (processor != null && option.equals("FogDistance")) {
            ((FogFilter) filters.get("Fog")).setFogDistance((float) value);
        } else if (processor != null && option.equals("FocusDistance")) {
            ((DepthOfFieldFilter) filters.get("DepthOfField")).setFocusDistance((float) value);
        } else if (processor != null && option.equals("FocusRange")) {
            ((DepthOfFieldFilter) filters.get("DepthOfField")).setFocusRange((float) value);
        } else if (processor != null && option.equals("DofBlurScale")) {
            ((DepthOfFieldFilter) filters.get("DepthOfField")).setBlurScale((float) value);
        } else if (processor != null && option.equals("DofBlurThreshold")) {
            ((DepthOfFieldFilter) filters.get("DepthOfField")).setBlurThreshold((float) value);
        } else if (processor != null && option.equals("SSAOApproximateNormals")) {
            ((SSAOFilter) filters.get("SSAO")).setApproximateNormals(value != 0);
        } else if (processor != null && option.equals("SSAOOnly")) {
            ((SSAOFilter) filters.get("SSAO")).setUseOnlyAo(value != 0);
        } else if (processor != null && option.equals("DofDebug")) {
            ((DepthOfFieldFilter) filters.get("DepthOfField")).setDebugUnfocus(value != 0);
        } else if (processor != null && (option.equals("FogRed") || option.equals("FogGreen") || option.equals("FogBlue"))) {
            FogFilter fog = (FogFilter) filters.get("Fog");
            com.jme3.math.ColorRGBA color = fog.getFogColor().clone();
            if (option.equals("FogRed")) {
                color.r = (float) value;
            } else if (option.equals("FogGreen")) {
                color.g = (float) value;
            } else {
                color.b = (float) value;
            }
            fog.setFogColor(color);
        } else if (filters.containsKey(option)) {
            filters.get(option).setEnabled(value != 0);
        }
    }

    void sync() {
        if (processor == null && supported) {
            create();
        }
        if (processor == null) {
            return;
        }
        var view = app.getViewPort();
        boolean other = false;
        for (var candidate : view.getProcessors()) {
            if (candidate instanceof FilterPostProcessor && candidate != processor) {
                other = true;
            }
        }
        boolean attached = view.getProcessors().contains(processor);
        boolean wanted = enabled && lit && !other;
        if (attached && !wanted) {
            view.removeProcessor(processor);
        } else if (!attached && wanted) {
            view.addProcessor(processor);
        }
    }
}

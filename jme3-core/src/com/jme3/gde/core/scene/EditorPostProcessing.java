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
 * Modern preview defaults to histogram automatic exposure, multi-scale Bloom,
 * Neutral tone mapping and FXAA; AO stays optional. LegacyPipeline shows the old
 * fixed ToneMap+FXAA reference preset; modern controls keep their settings.
 * Removing/switching preview resets adaptation and releases inactive GPU targets.
 * <pre>
 * 第一步：展开 Scene Details 的 Post processing，默认启用 HDR、ToneMap 和 FXAA。
 * 第二步：勾选所需效果，展开各效果子分组，调整参数；关闭效果会保留其参数。
 * 第三步：关闭 Global HDR / FPP 进行原始结果对照，再开启恢复配置。
 * 第四步：展开 Exposure，调节 Compensation (EV)；+1 加倍、-1 减半，0 恢复。
 * 第五步：Automatic exposure 默认开启，Min/Max 限制曝光乘数的 EV 范围，非 EV100。
 * 第六步：AO 的 Show Only 输出不经过后续曝光、Bloom、调色；取消后自动恢复。
 * 第七步：勾选 Legacy reference 查看旧的固定 ToneMap+FXAA；取消恢复新链及参数。
 * 新链支持曲线选择、饱和度、对比度和 RGB Gain，最后由输出帧缓冲编码一次。
 * 所有操作仅影响预览，不写入 J3O，不用于 GI 烘焙。
 * </pre>
 */
final class EditorPostProcessing {
    private final SceneApplication app;
    private final Map<String, Filter> filters = new LinkedHashMap<>();
    private FilterPostProcessor processor;
    private FilterPostProcessor legacyProcessor;
    private boolean legacy;
    private float minEV = -8f;
    private float maxEV = 8f;
    private float speedUp = 3f;
    private float speedDown = 1f;
    private Vector3f gain = new Vector3f(1, 1, 1);
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
        add("SSAO", new HorizonAOFilter(), false);
        add("Fog", new FogFilter(), false);
        add("DepthOfField", new DepthOfFieldFilter(), false);
        add("Exposure", new AutoExposureFilter(), true);
        add("Bloom", new CinematicBloomFilter(), true);
        add("ToneMap", new ColorGradingFilter(), true);
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
        if (option.equals("LegacyPipeline")) {
            legacy = value != 0;
        } else if (option.equals("Enabled")) {
            enabled = value != 0;
        } else if (option.equals("Lit")) {
            lit = value != 0;
        } else if (processor != null && option.equals("ExposureCompensation")) {
            ((AutoExposureFilter) filters.get("Exposure")).setExposureCompensation((float) value);
        } else if (processor != null && option.equals("BloomIntensity")) {
            ((CinematicBloomFilter) filters.get("Bloom")).setIntensity((float) value);
        } else if (processor != null && option.equals("WhitePoint")) {
            ((ColorGradingFilter) filters.get("ToneMap")).setWhitePoint((float) value);
        } else if (processor != null && option.equals("BloomThreshold")) {
            ((CinematicBloomFilter) filters.get("Bloom")).setThreshold((float) value);
        } else if (processor != null && option.equals("BloomSoftKnee")) {
            ((CinematicBloomFilter) filters.get("Bloom")).setSoftKnee((float) value);
        } else if (processor != null && option.equals("BloomScatter")) {
            ((CinematicBloomFilter) filters.get("Bloom")).setScatter((float) value);
        } else if (processor != null && option.equals("ResetExposure")) {
            ((AutoExposureFilter) filters.get("Exposure")).resetHistory();
        } else if (processor != null && option.equals("AutomaticExposure")) {
            ((AutoExposureFilter) filters.get("Exposure")).setAutomatic(value != 0);
        } else if (processor != null && option.equals("ExposureMin")) {
            minEV = Math.min((float) value, maxEV);
            ((AutoExposureFilter) filters.get("Exposure")).setExposureRange(minEV, maxEV);
        } else if (processor != null && option.equals("ExposureMax")) {
            maxEV = Math.max((float) value, minEV);
            ((AutoExposureFilter) filters.get("Exposure")).setExposureRange(minEV, maxEV);
        } else if (processor != null && option.equals("ExposureSpeedUp")) {
            speedUp = (float) value;
            ((AutoExposureFilter) filters.get("Exposure")).setAdaptationSpeeds(speedUp, speedDown);
        } else if (processor != null && option.equals("ExposureSpeedDown")) {
            speedDown = (float) value;
            ((AutoExposureFilter) filters.get("Exposure")).setAdaptationSpeeds(speedUp, speedDown);
        } else if (processor != null && option.equals("FilmicCurve")) {
            ((ColorGradingFilter) filters.get("ToneMap")).setCurve(value != 0
                    ? ColorGradingFilter.Curve.Filmic : ColorGradingFilter.Curve.Neutral);
        } else if (processor != null && option.equals("Saturation")) {
            ((ColorGradingFilter) filters.get("ToneMap")).setSaturation((float) value);
        } else if (processor != null && option.equals("Contrast")) {
            ((ColorGradingFilter) filters.get("ToneMap")).setContrast((float) value);
        } else if (processor != null && option.startsWith("Gain")) {
            if (option.equals("GainRed")) {
                gain.x = (float) value;
            } else if (option.equals("GainGreen")) {
                gain.y = (float) value;
            } else if (option.equals("GainBlue")) {
                gain.z = (float) value;
            }
            ((ColorGradingFilter) filters.get("ToneMap")).setGain(gain);
        } else if (processor != null && option.equals("FXAASubPixelShift")) {
            ((FXAAFilter) filters.get("FXAA")).setSubPixelShift((float) value);
        } else if (processor != null && option.equals("FXAASpanMax")) {
            ((FXAAFilter) filters.get("FXAA")).setSpanMax((float) value);
        } else if (processor != null && option.equals("FXAAReduceMul")) {
            ((FXAAFilter) filters.get("FXAA")).setReduceMul((float) value);
        } else if (processor != null && option.equals("FXAAVxOffset")) {
            ((FXAAFilter) filters.get("FXAA")).setVxOffset((float) value);
        } else if (processor != null && option.equals("SSAORadius")) {
            ((HorizonAOFilter) filters.get("SSAO")).setRadius((float) value);
        } else if (processor != null && option.equals("SSAOIntensity")) {
            ((HorizonAOFilter) filters.get("SSAO")).setIntensity((float) value);
        } else if (processor != null && option.equals("SSAOBias")) {
            ((HorizonAOFilter) filters.get("SSAO")).setBias((float) value);
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
        } else if (processor != null && option.equals("SSAOOnly")) {
            ((HorizonAOFilter) filters.get("SSAO")).setShowOnly(value != 0);
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
        if (legacy && legacyProcessor == null) {
            // Reference preset only: controls above retain the modern settings.
            legacyProcessor = new FilterPostProcessor(app.getAssetManager());
            legacyProcessor.setFrameBufferFormat(app.getRenderer().getCaps().contains(Caps.HalfFloatColorBufferRGBA)
                    ? Image.Format.RGBA16F : Image.Format.RGBA32F);
            legacyProcessor.addFilter(new ToneMapFilter());
            legacyProcessor.addFilter(new FXAAFilter());
        }
        FilterPostProcessor selected = legacy ? legacyProcessor : processor;
        FilterPostProcessor inactive = legacy ? processor : legacyProcessor;
        if (inactive != null && view.getProcessors().contains(inactive)) {
            view.removeProcessor(inactive);
            inactive.cleanup();
        }
        boolean other = false;
        for (var candidate : view.getProcessors()) {
            if (candidate instanceof FilterPostProcessor && candidate != selected) {
                other = true;
            }
        }
        boolean attached = view.getProcessors().contains(selected);
        boolean wanted = enabled && lit && !other;
        if (attached && !wanted) {
            view.removeProcessor(selected);
            selected.cleanup();
        } else if (!attached && wanted) {
            ((AutoExposureFilter) filters.get("Exposure")).resetHistory();
            view.addProcessor(selected);
        }
    }
}

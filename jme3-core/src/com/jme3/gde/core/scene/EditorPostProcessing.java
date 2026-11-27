package com.jme3.gde.core.scene;

import com.jme3.post.Filter;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.WorldSettings.DisplayOutput;
import com.jme3.post.filters.*;
import com.jme3.post.ssao.SSAOFilter;
import com.jme3.math.Vector3f;
import com.jme3.texture.Image;
import com.jme3.renderer.Caps;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scene-owned HDR configuration with render-thread-only preview GPU state. Tone mapping
 * and FXAA start enabled; optional effects start disabled. A scene-owned FPP
 * or non-Lit diagnostic view bypasses this chain to avoid processing twice.
 * RGBA16F is preferred, RGBA32F is the fallback; unsupported GPUs bypass it.
 * This processor never attaches to GI capture viewports. Explicit edits snapshot
 * configuration into the active root WorldSettings for normal J3O serialization.
 * Modern preview defaults to histogram automatic exposure, multi-scale Bloom,
 * Neutral tone mapping and FXAA; AO stays optional. LegacyPipeline shows the old
 * fixed ToneMap+FXAA reference preset; modern controls keep their settings.
 * AntiAliasing: 0=off, 1=FXAA (default), 2=TAA. TAA runs first in single-sample
 * HDR; FXAA runs after tone mapping. The legacy reference retains fixed FXAA.
 * Removing/switching preview resets adaptation and releases inactive GPU targets.
 * <pre>
 * 第一步：展开 Scene Details 的 Post processing，默认启用 HDR、ToneMap 和 FXAA。
 * 第二步：勾选所需效果，展开各效果子分组，调整参数；关闭效果会保留其参数。
 * 第三步：关闭 Global HDR / FPP 进行原始结果对照，再开启恢复配置。
 * 第四步：展开 Exposure，调节 Compensation (EV)；+1 加倍、-1 减半，0 恢复。
 * 第五步：Automatic exposure 默认开启，Min/Max 限制曝光乘数的 EV 范围，非 EV100。
 * 第六步：AO 的 Show Only 输出不经过后续曝光、Bloom、调色；取消后自动恢复。
 * 第七步：勾选 Legacy reference 查看旧的固定 ToneMap+FXAA；取消恢复新链及参数。
 * Shader sRGB output 开关只控制编辑器主 AWT 视口，默认开启；显式关闭可恢复旧输出。
 * 开启后先在线性浮点目标合成，再由 shader 编码读回；关闭恢复硬件 sRGB 目标。
 * 该 shader 预览选项随编辑器配置保存。新场景的 Game display output 默认选择 SDR。
 * 选择 HDR (scRGB) 可在兼容的 Windows 3DViewer 中预览，游戏输出保存到 WorldSettings。
 * 新链支持曲线选择、饱和度、对比度和 RGB Gain，最后由呈现阶段编码一次。
 * Upscaling 默认 Off；选择 FSR1/FSR2 并调整 Render resolution scale，GUI 保持输出尺寸。
 * FSR2 自动旁路普通 TAA/FXAA，关闭后恢复原选择；FSR1 保留前置抗锯齿。
 * 超分配置随 FPP 写入 WorldSettings，标准游戏挂载场景后恢复；需要配套 LWJGL3 引擎。
 * 窗口 shader sRGB 默认由 AppSettings 开启，不依赖此处的预览开关。
 * 抗锯齿分组选择 Off / FXAA / TAA；切换保留参数，TAA 从新历史开始。
 * 选择 TAA 或 FSR2 后，在 Post processing 的 Opaque velocity pass 选择速度生成方式。
 * 默认 GBufferPass；选择 Separate Pass 重新绘制不透明物体的速度，切回可恢复。
 * 保存场景后 TAA 和 FSR2 共用新选择；旧场景原有的 TAA 选项保留到首次切换。
 * 第八步：保存场景将 WorldSettings 写入 J3O；游戏加载并挂到 rootNode 后自动恢复。
 * 第九步：Exposure 勾选 Histogram adaptation，ToneMap 勾选 Film / combined LUT。
 * 新模式使用亮度上下限和 Film 参数；Combine LUTs 分组调节 Gamma、Offset 和分区增益。
 * 取消这两个开关恢复旧模式；旧场景不会自动迁移。外部 LUT 纹理由 Java API 设置。
 * 切换场景会恢复各自设置和曝光历史；旧场景未编辑时使用默认预览，不用于 GI 烘焙。
 * </pre>
 */
final class EditorPostProcessing {
    private final SceneApplication app;
    private final Map<String, Double> options = new LinkedHashMap<>();
    private SceneRequest owner;
    private final Map<String, Filter> filters = new LinkedHashMap<>();
    private FilterPostProcessor processor;
    private FilterPostProcessor legacyProcessor;
    private boolean legacy;
    private float minEV = -8f;
    private float maxEV = 8f;
    private float speedUp = 3f;
    private float speedDown = 1f;
    private void migrateOption(String oldKey, String key) {
        Double value = options.remove(oldKey);
        if (value != null) {
            options.putIfAbsent(key, value);
        }
    }

    private Vector3f gain = new Vector3f(1, 1, 1);
    private boolean enabled = true;
    private boolean lit = true;
    private boolean supported = true;
    private DisplayOutput gameDisplayOutput = DisplayOutput.Sdr;

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
        processor.setNumSamples(1);
        add("TAA", new TemporalAAFilter(), false);
        add("SSAO", new HorizonAOFilter(), false);
        add("Fog", new FogFilter(), false);
        add("DepthOfField", new DepthOfFieldFilter(), false);
        add("Exposure", new AutoExposureFilter(), true);
        add("Bloom", new CinematicBloomFilter(), true);
        add("ToneMap", new ColorGradingFilter(), true);
        add("FXAA", new FXAAFilter(), true);
        add("Upscaling", new UpscalingPass(), false);
    }

    private void add(String key, Filter filter, boolean active) {
        filter.setEnabled(active);
        filters.put(key, filter);
        processor.addFilter(filter);
    }

    void configure(String option, double value) {
        if (option.equals("GameDisplayOutput")) {
            gameDisplayOutput = value == 2 ? DisplayOutput.Hdr
                    : value == 1 ? DisplayOutput.Sdr : DisplayOutput.Application;
            return;
        }
        if (option.equals("FXAA") || option.equals("TAA")) {
            int mode = option.equals("TAA") ? 2 : 1;
            if (value != 0 || options.getOrDefault("AntiAliasing", 1d) == mode) {
                configure("AntiAliasing", value != 0 ? mode : 0);
            }
            return;
        }
        if (option.equals("TAABaseVelocity") && options.containsKey("OpaqueVelocityPass")) {
            // Once a shared choice exists, the legacy setter updates that choice.
            configure("OpaqueVelocityPass", value != 0 ? 0 : 1);
            return;
        }
        if (option.equals("OpaqueVelocityPass")) {
            options.remove("TAABaseVelocity");
        }
        if (!option.equals("Lit") && !option.equals("ResetExposure")) {
            options.put(option, value);
        }
        if (processor == null && supported) {
            create();
        }
        if (processor != null && option.equals("Upscaling")) {
            UpscalingPass upscale = (UpscalingPass) filters.get("Upscaling");
            upscale.setAlgorithm(value == 2 ? UpscalingPass.Algorithm.FSR2 : UpscalingPass.Algorithm.FSR1);
            upscale.setEnabled(value != 0);
            ((TemporalAAFilter) filters.get("TAA")).resetHistory();
        } else if (processor != null && option.equals("UpscalingScale")) {
            ((UpscalingPass) filters.get("Upscaling")).setRenderScale((float) value);
        } else if (processor != null && option.equals("UpscalingSharpness")) {
            ((UpscalingPass) filters.get("Upscaling")).setSharpness((float) value);
        } else if (processor != null && option.equals("AntiAliasing")) {
            filters.get("TAA").setEnabled(value == 2);
            filters.get("FXAA").setEnabled(value == 1);
            ((TemporalAAFilter) filters.get("TAA")).resetHistory();
        } else if (processor != null && option.equals("TAACurrentWeight")) {
            ((TemporalAAFilter) filters.get("TAA")).setCurrentFrameWeight((float) value);
        } else if (processor != null && option.equals("OpaqueVelocityPass")) {
            boolean basePass = value == 0;
            ((TemporalAAFilter) filters.get("TAA")).setBasePassOutputsVelocity(basePass);
            ((UpscalingPass) filters.get("Upscaling")).setBasePassOutputsVelocity(basePass);
        } else if (processor != null && option.equals("TAABaseVelocity")) {
            // Old scenes used this only for TAA; FSR2 keeps the existing integrated opaque velocity default.
            ((TemporalAAFilter) filters.get("TAA")).setBasePassOutputsVelocity(value != 0);
        } else if (processor != null && option.equals("TAATransparentVelocity")) {
            ((TemporalAAFilter) filters.get("TAA")).setTranslucentVelocityEnabled(value != 0);
        } else if (processor != null && option.equals("TAAAlphaThreshold")) {
            ((TemporalAAFilter) filters.get("TAA")).setTranslucentAlphaThreshold((float) value);
        } else if (processor != null && option.equals("HistogramExposure")) {
            ((AutoExposureFilter) filters.get("Exposure")).setMode(value != 0
                    ? AutoExposureFilter.Mode.SceneHistogram : AutoExposureFilter.Mode.Legacy);
        } else if (processor != null && java.util.Set.of("MinBrightness", "MaxBrightness", "HistogramMin", "HistogramMax", "TransitionDistance", "LowPercent", "HighPercent").contains(option)) {
            AutoExposureFilter exposure = (AutoExposureFilter) filters.get("Exposure");
            double min = options.getOrDefault("MinBrightness", .03d);
            double max = Math.max(min, options.getOrDefault("MaxBrightness", 2d));
            options.put("MaxBrightness", max);
            exposure.setBrightnessRange((float) min, (float) max);
            min = options.getOrDefault("HistogramMin", -8d);
            max = Math.max(min + .01, options.getOrDefault("HistogramMax", 4d));
            options.put("HistogramMax", max);
            exposure.setHistogramRange((float) min, (float) max);
            exposure.setTransitionDistance(options.getOrDefault("TransitionDistance", 1.5d).floatValue());
            double low = options.getOrDefault("LowPercent", .1d);
            double high = Math.max(low + .01, options.getOrDefault("HighPercent", .9d));
            options.put("HighPercent", high);
            exposure.setPercentiles((float) low, (float) high);
        } else if (processor != null && (option.equals("FilmLutTonemap") || option.startsWith("Film")
                || option.equals("Temperature") || option.equals("Tint") || option.equals("BlueCorrection") || option.equals("ExpandGamut")
                || option.startsWith("GradingGamma") || option.startsWith("GradingOffset")
                || option.startsWith("ShadowGain") || option.startsWith("MidtoneGain") || option.startsWith("HighlightGain"))) {
            configureGrading();
        } else if (option.equals("LegacyPipeline")) {
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
            options.put(option, (double) minEV);
            ((AutoExposureFilter) filters.get("Exposure")).setExposureRange(minEV, maxEV);
        } else if (processor != null && option.equals("ExposureMax")) {
            maxEV = Math.max((float) value, minEV);
            options.put(option, (double) maxEV);
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

    private Vector3f optionVector(String prefix, double fallback) {
        return new Vector3f(options.getOrDefault(prefix + "Red", fallback).floatValue(),
                options.getOrDefault(prefix + "Green", fallback).floatValue(),
                options.getOrDefault(prefix + "Blue", fallback).floatValue());
    }

    private void configureGrading() {
        ColorGradingFilter grade = (ColorGradingFilter) filters.get("ToneMap");
        grade.setCurve(options.getOrDefault("FilmLutTonemap", 0d) != 0 ? ColorGradingFilter.Curve.FilmLut
                : options.getOrDefault("FilmicCurve", 0d) != 0 ? ColorGradingFilter.Curve.Filmic
                : ColorGradingFilter.Curve.Neutral);
        grade.setFilm(options.getOrDefault("FilmSlope", .88d).floatValue(),
                options.getOrDefault("FilmToe", .55d).floatValue(),
                options.getOrDefault("FilmShoulder", .26d).floatValue(),
                options.getOrDefault("FilmBlackClip", 0d).floatValue(),
                options.getOrDefault("FilmWhiteClip", .04d).floatValue());
        grade.setWhiteBalance(options.getOrDefault("Temperature", 6500d).floatValue(),
                options.getOrDefault("Tint", 0d).floatValue());
        grade.setExpandGamut(options.getOrDefault("ExpandGamut", 1d).floatValue());
        grade.setBlueCorrection(options.getOrDefault("BlueCorrection", .6d).floatValue());
        grade.setGamma(optionVector("GradingGamma", 1d));
        grade.setOffset(optionVector("GradingOffset", 0d));
        grade.setTonalGains(optionVector("ShadowGain", 1d), optionVector("MidtoneGain", 1d),
                optionVector("HighlightGain", 1d));
    }

    private void ensureLegacy() {
        if (legacy && legacyProcessor == null && supported) {
            legacyProcessor = new FilterPostProcessor(app.getAssetManager());
            legacyProcessor.setFrameBufferFormat(app.getRenderer().getCaps().contains(Caps.HalfFloatColorBufferRGBA)
                    ? Image.Format.RGBA16F : Image.Format.RGBA32F);
            legacyProcessor.addFilter(new ToneMapFilter());
            legacyProcessor.addFilter(new FXAAFilter());
        }
    }

    void open(SceneRequest request) {
        close();
        owner = request;
        enabled = true;
        legacy = false;
        minEV = -8;
        maxEV = 8;
        speedUp = 3;
        speedDown = 1;
        gain = new Vector3f(1, 1, 1);
        supported = true;
        create();
        if (request.getRootNode() != null) {
            var settings = request.getRootNode().getControl(com.jme3.post.WorldSettings.class);
            if (settings != null) {
                gameDisplayOutput = settings.getDisplayOutput();
                // Restore the pair before replaying either endpoint, so map insertion
                // order cannot clamp a previously valid exposure range.
                minEV = settings.getEditorOptions().getOrDefault("ExposureMin", -8d).floatValue();
                maxEV = settings.getEditorOptions().getOrDefault("ExposureMax", 8d).floatValue();
                options.putAll(settings.getEditorOptions());
                if (options.containsKey("OpaqueVelocityPass")) {
                    options.remove("TAABaseVelocity");
                }
                migrateOption("UE\u0034Exposure", "HistogramExposure");
                migrateOption("UE\u0034Tonemap", "FilmLutTonemap");
                for (var entry : new LinkedHashMap<>(options).entrySet()) {
                    if (!entry.getKey().equals("FXAA") && !entry.getKey().equals("TAA")) {
                        configure(entry.getKey(), entry.getValue());
                    }
                }
                configure("AntiAliasing", options.getOrDefault("AntiAliasing",
                        options.getOrDefault("TAA", 0d) != 0 ? 2d
                        : options.getOrDefault("FXAA", 1d) != 0 ? 1d : 0d));
                options.remove("FXAA");
                options.remove("TAA");
                enabled = settings.isEnabled();
                options.put("Enabled", enabled ? 1d : 0d);
            }
        }
    }

    Map<String, Double> options() {
        Map<String, Double> snapshot = new LinkedHashMap<>(options);
        snapshot.put("PostProcessingSupported", supported ? 1d : 0d);
        snapshot.put("GameDisplayOutput", gameDisplayOutput == DisplayOutput.Hdr ? 2d
                : gameDisplayOutput == DisplayOutput.Sdr ? 1d : 0d);
        return snapshot;
    }

    void saveSettings(SceneRequest request) throws java.io.IOException {
        saveSettings(request, false);
    }

    void saveSettings(SceneRequest request, boolean displayOnly) throws java.io.IOException {
        if (owner != request || request.getRootNode() == null || (!displayOnly && processor == null)) {
            return;
        }
        var root = request.getRootNode();
        var settings = root.getControl(com.jme3.post.WorldSettings.class);
        boolean added = settings == null;
        if (added) {
            settings = new com.jme3.post.WorldSettings();
        }
        if (!displayOnly) {
            ensureLegacy();
            settings.setPostProcessing(legacy ? legacyProcessor : processor, options);
            settings.setEnabled(enabled);
        }
        settings.setDisplayOutput(gameDisplayOutput);
        if (added) {
            root.addControl(settings);
        }
    }

    void close() {
        app.setPreviewPresentation(true, false, false);
        for (FilterPostProcessor chain : new FilterPostProcessor[]{processor, legacyProcessor}) {
            if (chain != null && app.getViewPort().getProcessors().contains(chain)) {
                app.getViewPort().removeProcessor(chain);
            }
        }
        processor = null;
        legacyProcessor = null;
        filters.clear();
        options.clear();
        gameDisplayOutput = DisplayOutput.Sdr;
        owner = null;
    }

    void sync() {
        app.setPreviewPresentation(options.getOrDefault("ShaderSrgbOutput", 1d) != 0,
                options.getOrDefault("SrgbOutputDithering", 0d) != 0,
                supported && owner != null && gameDisplayOutput == DisplayOutput.Hdr);
        if (owner == null) {
            return;
        }
        if (processor == null && supported) {
            create();
        }
        if (processor == null) {
            return;
        }
        var view = app.getViewPort();
        ensureLegacy();
        FilterPostProcessor selected = legacy ? legacyProcessor : processor;
        FilterPostProcessor inactive = legacy ? processor : legacyProcessor;
        if (inactive != null && view.getProcessors().contains(inactive)) {
            view.removeProcessor(inactive);

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

        } else if (!attached && wanted) {
            ((AutoExposureFilter) filters.get("Exposure")).resetHistory();
            view.addProcessor(selected);
        }
    }
}

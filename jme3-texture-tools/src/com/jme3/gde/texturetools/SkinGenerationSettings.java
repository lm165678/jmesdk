package com.jme3.gde.texturetools;

import com.jme3.material.skin.SkinLutGenerator;

/** Immutable snapshot shared by LUT generation and normal prefiltering. */
final class SkinGenerationSettings {
    final SkinLutGenerator.Profile profile;
    final int width;
    final int height;
    final float curvature;
    final int samples;
    final String description;

    SkinGenerationSettings(SkinLutGenerator.Profile profile, int width, int height, float curvature, int samples, String description) {
        this.profile = profile;
        this.width = width;
        this.height = height;
        this.curvature = curvature;
        this.samples = samples;
        this.description = description;
    }

    SkinLutGenerator.Result generateLut() {
        return SkinLutGenerator.generate(profile, width, height, curvature, samples);
    }
}

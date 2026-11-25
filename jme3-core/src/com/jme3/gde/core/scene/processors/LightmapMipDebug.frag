#import "Common/ShaderLib/GLSLCompat.glsllib"
uniform sampler2D m_Lightmap;
uniform bool m_MipSampling;
uniform float m_MaxMip;
uniform float m_AtlasInset;
uniform int m_State;
varying vec2 lightmapUv;
vec3 mipColor(float level) {
    if (level < 0.5) {
        return vec3(0.0, 0.8, 0.1);
    }
    if (level < 1.5) {
        return vec3(0.0, 0.8, 0.9);
    }
    if (level < 2.5) {
        return vec3(0.1, 0.2, 1.0);
    }
    if (level < 3.5) {
        return vec3(0.6, 0.1, 1.0);
    }
    if (level < 4.5) {
        return vec3(1.0, 0.45, 0.0);
    }
    return vec3(0.8, 0.05, 0.05);
}
void main() {
    // This shader exists only in the selected SDK diagnostic mode. No imports
    // or branches are added to PBR/Phong or normal GI sampling shaders.
    if (m_State == 1) {
        gl_FragColor = vec4(1.0, 0.8, 0.0, 1.0);
        return;
    }
    if (m_State == 2) {
        gl_FragColor = vec4(0.8, 0.0, 0.0, 1.0);
        return;
    }
    if (m_State == 3) {
        gl_FragColor = vec4(1.0, 0.0, 0.8, 1.0);
        return;
    }
    if (m_State == 4) {
        gl_FragColor = vec4(0.35, 0.35, 0.35, 1.0);
        return;
    }
    if (m_State == 5) {
        gl_FragColor = vec4(1.0, 0.35, 0.0, 1.0);
        return;
    }
    vec2 uv = lightmapUv;
    if (m_AtlasInset > 0.0) {
        uv.y = clamp(uv.y * 0.5, m_AtlasInset, 0.5 - m_AtlasInset);
    }
    // Query the ACTUAL bound lightmap and sampler, not camera distance. The
    // first component is the mip array level selected for this lookup. Clamp
    // to baked levels, including intentionally truncated chart-safe chains.
    float level = m_MipSampling ? clamp(textureQueryLod(m_Lightmap, uv).x, 0.0, m_MaxMip) : 0.0;
    vec3 color = mix(mipColor(floor(level)), mipColor(ceil(level)), fract(level));
    gl_FragColor = vec4(color, 1.0);
}

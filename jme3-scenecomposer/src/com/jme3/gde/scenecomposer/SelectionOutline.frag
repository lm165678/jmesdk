#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler2D m_Mask;
uniform vec2 m_PixelStep;
uniform vec4 m_OutlineColor;
varying vec2 uv;

void main() {
    if (texture2D(m_Mask, uv).r > 0.5) {
        discard;
    }
    vec2 x = vec2(m_PixelStep.x, 0.0);
    vec2 y = vec2(0.0, m_PixelStep.y);
    vec2 diagonal = m_PixelStep * 0.7071;
    float edge = max(texture2D(m_Mask, uv + x).r, texture2D(m_Mask, uv - x).r);
    edge = max(edge, max(texture2D(m_Mask, uv + y).r, texture2D(m_Mask, uv - y).r));
    edge = max(edge, max(texture2D(m_Mask, uv + diagonal).r, texture2D(m_Mask, uv - diagonal).r));
    diagonal.y = -diagonal.y;
    edge = max(edge, max(texture2D(m_Mask, uv + diagonal).r, texture2D(m_Mask, uv - diagonal).r));
    if (edge < 0.5) {
        discard;
    }
    gl_FragColor = m_OutlineColor;
}

#import "Common/ShaderLib/GLSLCompat.glsllib"
uniform sampler2D m_Source;
uniform sampler2D m_Header;
uniform sampler2D m_Depth;
uniform bool m_DepthView;
varying vec2 texCoord;
void main() {
    if (texture2D(m_Header, texCoord).a < 1.0) {
        discard;
    }
    float depth = texture2D(m_Depth, texCoord).r;
    gl_FragDepth = depth;
    vec3 value = abs(texture2D(m_Source, texCoord).rgb);
    if (m_DepthView) {
        value = vec3(pow(max(0.0, 1.0 - depth), 0.25));
    } else {
        value = value / (vec3(1.0) + value);
    }
    gl_FragColor = vec4(value, 1.0);
}

#import "Common/ShaderLib/GLSLCompat.glsllib"

#ifdef HAS_COLORMAP
    uniform sampler2D m_ColorMap;
    uniform float m_AlphaDiscardThreshold;
    varying vec2 texCoord1;
#endif

void main() {
    #ifdef HAS_COLORMAP
        if (texture2D(m_ColorMap, texCoord1).a < m_AlphaDiscardThreshold) {
            discard;
        }
    #endif
    gl_FragColor = vec4(1.0);
}

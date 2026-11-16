#import "Common/ShaderLib/GLSLCompat.glsllib"
attribute vec4 inPosition;
uniform vec4 m_ScreenRect;
varying vec2 texCoord;
void main() {
    // texCoord remains viewport UV, not UV relative to the cropped rectangle.
    texCoord = mix(m_ScreenRect.xy, m_ScreenRect.zw, inPosition.xy);
    gl_Position = vec4(texCoord * 2.0 - 1.0, 0.0, 1.0);
}

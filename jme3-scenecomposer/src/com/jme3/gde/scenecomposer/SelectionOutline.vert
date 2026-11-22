#import "Common/ShaderLib/GLSLCompat.glsllib"

attribute vec3 inPosition;
varying vec2 uv;

void main() {
    uv = inPosition.xy;
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}

#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/Instancing.glsllib"
#import "Common/ShaderLib/Skinning.glsllib"
#import "Common/ShaderLib/MorphAnim.glsllib"
attribute vec3 inPosition;
#ifdef HAS_UV2
attribute vec2 inTexCoord2;
#endif
varying vec2 lightmapUv;
void main() {
    vec4 position = vec4(inPosition, 1.0);
    #ifdef NUM_MORPH_TARGETS
        Morph_Compute(position);
    #endif
    #ifdef NUM_BONES
        Skinning_Compute(position);
    #endif
    gl_Position = TransformWorldViewProjection(position);
    lightmapUv = vec2(0.0);
    #ifdef HAS_UV2
        lightmapUv = inTexCoord2;
    #endif
}

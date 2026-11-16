// Based on TestSdfSurface / Materials/Sdf/Surface.frag.
// SDK discards misses and writes hit depth to compose selected subtree volumes.
#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler3D m_DistanceField;
uniform vec3 m_BoundsMin;
uniform vec3 m_BoundsMax;
uniform vec3 m_Resolution;
uniform vec3 m_CellSize;
uniform mat4 m_ClipToLocal;
uniform mat4 m_LocalToClip;
uniform float m_HitDistance;
uniform bool m_StepHeatmap;
varying vec2 texCoord;

#ifdef GLOBAL_PREVIEW
uniform sampler3D m_Field0;
uniform sampler3D m_Field1;
uniform sampler3D m_Field2;
uniform vec3 m_Min0;
uniform vec3 m_Min1;
uniform vec3 m_Min2;
uniform vec3 m_Cells;
uniform float m_GlobalResolution;
uniform mat4 m_LocalToWorld;
uniform float m_DistanceScale;
uniform float m_SourceMargin;
uniform int m_Cascade;
uniform bool m_CascadeColors;

vec3 cascadeMin(int level) {
    return level == 0 ? m_Min0 : (level == 1 ? m_Min1 : m_Min2);
}
int cascadeAt(vec3 point) {
    if (m_Cascade >= 0) {
        return m_Cascade;
    }
    for (int level = 0; level < 2; level++) {
        vec3 lo = cascadeMin(level) + vec3(m_Cells[level]);
        vec3 hi = cascadeMin(level) + vec3(m_Cells[level] * (m_GlobalResolution - 2.0));
        if (all(greaterThanEqual(point, lo)) && all(lessThanEqual(point, hi))) {
            return level;
        }
    }
    return 2;
}
float globalDistance(vec3 point, int level) {
    vec3 uvw = ((point - cascadeMin(level)) / m_Cells[level] + vec3(0.5)) / m_GlobalResolution;
    if (level == 0) {
        return texture(m_Field0, uvw).r;
    }
    if (level == 1) {
        return texture(m_Field1, uvw).r;
    }
    return texture(m_Field2, uvw).r;
}
// Clip in local-ray t units, including nonuniform and mirrored transforms.
bool clipGlobal(vec3 origin, vec3 direction, inout float start, inout float finish) {
    int outer = m_Cascade >= 0 ? m_Cascade : 2;
    vec3 lo = cascadeMin(outer);
    vec3 hi = lo + vec3(m_Cells[outer] * (m_GlobalResolution - 1.0));
    for (int axis = 0; axis < 3; axis++) {
        if (abs(direction[axis]) < 1e-8) {
            if (origin[axis] < lo[axis] || origin[axis] > hi[axis]) {
                return false;
            }
        } else {
            float a = (lo[axis] - origin[axis]) / direction[axis];
            float b = (hi[axis] - origin[axis]) / direction[axis];
            start = max(start, min(a, b));
            finish = min(finish, max(a, b));
        }
    }
    return finish >= start;
}
#endif

float distanceAt(vec3 position) {
    vec3 grid = (position - m_BoundsMin) / (m_BoundsMax - m_BoundsMin);
    vec3 uvw = (grid * (m_Resolution - vec3(1.0)) + vec3(0.5)) / m_Resolution;
    return texture(m_DistanceField, uvw).r;
}

bool intersectBounds(vec3 origin, vec3 direction, out float nearT, out float farT) {
    nearT = 0.0;
    farT = 1e30;
    for (int axis = 0; axis < 3; axis++) {
        if (abs(direction[axis]) < 1e-8) {
            if (origin[axis] < m_BoundsMin[axis] || origin[axis] > m_BoundsMax[axis]) {
                return false;
            }
        } else {
            float a = (m_BoundsMin[axis] - origin[axis]) / direction[axis];
            float b = (m_BoundsMax[axis] - origin[axis]) / direction[axis];
            nearT = max(nearT, min(a, b));
            farT = min(farT, max(a, b));
        }
    }
    return farT >= nearT;
}

vec3 surfaceNormal(vec3 position, vec3 rayDirection) {
    vec3 dx = vec3(m_CellSize.x * 0.5, 0.0, 0.0);
    vec3 dy = vec3(0.0, m_CellSize.y * 0.5, 0.0);
    vec3 dz = vec3(0.0, 0.0, m_CellSize.z * 0.5);
    vec3 gradient = vec3(
            (distanceAt(position + dx) - distanceAt(position - dx)) / m_CellSize.x,
            (distanceAt(position + dy) - distanceAt(position - dy)) / m_CellSize.y,
            (distanceAt(position + dz) - distanceAt(position - dz)) / m_CellSize.z);
    vec3 normal = dot(gradient, gradient) > 1e-12 ? normalize(gradient) : -rayDirection;
    return dot(normal, rayDirection) > 0.0 ? -normal : normal;
}

void main() {
    vec2 clip = texCoord * 2.0 - 1.0;
    vec4 nearPoint = m_ClipToLocal * vec4(clip, -1.0, 1.0);
    vec4 farPoint = m_ClipToLocal * vec4(clip, 1.0, 1.0);
    vec3 origin = nearPoint.xyz / nearPoint.w;
    vec3 end = farPoint.xyz / farPoint.w;
    vec3 direction = normalize(end - origin);
    gl_FragColor = vec4(0.025, 0.035, 0.055, 1.0);
    float nearT;
    float farT;
    if (!intersectBounds(origin, direction, nearT, farT)) {
        discard;
    }
    farT = min(farT, length(end - origin));
#ifdef GLOBAL_PREVIEW
    vec3 worldOrigin = (m_LocalToWorld * vec4(origin, 1.0)).xyz;
    vec3 worldDirection = (m_LocalToWorld * vec4(direction, 0.0)).xyz;
    if (!clipGlobal(worldOrigin, worldDirection, nearT, farT)) {
        discard;
    }
    float rayScale = max(length(worldDirection), 1e-8);
#endif
    float t = nearT;
    float epsilon = min(m_CellSize.x, min(m_CellSize.y, m_CellSize.z)) * 0.02;
    const int maxSteps = 512;
    for (int step = 0; step < maxSteps; step++) {
        if (t > farT) {
            discard;
        }
        vec3 position = origin + direction * t;
#ifdef GLOBAL_PREVIEW
        vec3 worldPoint = worldOrigin + worldDirection * t;
        int level = cascadeAt(worldPoint);
#endif
        float distance = abs(distanceAt(position));
        if (distance <= m_HitDistance + epsilon) {
            vec3 normal = surfaceNormal(position, direction);
            float diffuse = max(0.0, dot(normal, normalize(vec3(0.4, 0.8, 0.3))));
            float rim = pow(1.0 - max(0.0, dot(normal, -direction)), 3.0);
            vec3 color = vec3(0.65, 0.75, 0.85) * (0.2 + 0.8 * diffuse) + 0.15 * rim;
#ifdef GLOBAL_PREVIEW
            if (m_CascadeColors) {
                vec3 tint = level == 0 ? vec3(0.3, 0.8, 0.95)
                        : (level == 1 ? vec3(0.45, 0.95, 0.45) : vec3(0.95, 0.65, 0.2));
                color = tint * (0.2 + 0.8 * diffuse) + 0.15 * rim;
            }
#endif
            if (m_StepHeatmap) {
                float cost = min(1.0, float(step) / 128.0);
                color = mix(vec3(0.05, 0.2, 0.8), vec3(1.0, 0.25, 0.05), cost);
            }
            vec4 clipHit = m_LocalToClip * vec4(position, 1.0);
            gl_FragDepth = (clipHit.z / clipHit.w) * 0.5 + 0.5;
            gl_FragColor = vec4(color, 1.0);
            return;
        }
        // Debug reconstruction only: interpolated samples are not exact distances.
        // The relaxed step and finite hit band are NOT a certified DDGI ray query.
        float advance = max((distance - m_HitDistance) * 0.7, epsilon * 0.25);
#ifdef GLOBAL_PREVIEW
        // A global union can be much closer than this individual source volume.
        // It must never replace a larger safe source step with tiny cell steps.
        float uncertainty = (m_SourceMargin + m_HitDistance + epsilon) * m_DistanceScale
                + 0.866025404 * m_Cells[level];
        float coarse = globalDistance(worldPoint, level) - uncertainty;
        advance = max(advance, min(max(coarse, 0.0) * 0.7, m_Cells.x * 0.5) / rayScale);
#endif
        t += advance;
    }
    // Magenta distinguishes iteration exhaustion from a real background miss.
    gl_FragDepth = 1.0;
    gl_FragColor = vec4(0.8, 0.0, 0.6, 1.0);
}

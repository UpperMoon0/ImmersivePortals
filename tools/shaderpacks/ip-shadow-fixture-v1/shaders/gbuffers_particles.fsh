#version 150 compatibility
uniform sampler2D shadowtex0;
in vec3 worldPosition;
const int shadowMapResolution = 256;
const float shadowDistance = 32.0;
const bool shadowHardwareFiltering = false;
const bool shadowtex0Nearest = true;
/* RENDERTARGETS: 0 */
void main() {
    vec2 uv = vec2(worldPosition.x, worldPosition.y - 82.0) / 32.0 + 0.5;
    float receiverDepth = 0.5 - worldPosition.z / 64.0;
    float blockerDepth = texture(shadowtex0, uv).r;
    bool shadowed = blockerDepth + 0.002 < receiverDepth;
    vec3 color = shadowed ? vec3(0.05, 0.15, 0.85) : vec3(0.1, 0.85, 0.1);
    if (worldPosition.z > 0.25) color = vec3(1.0, 0.0, 0.0);
    gl_FragData[0] = vec4(color, 1.0);
}

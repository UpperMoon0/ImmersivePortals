#version 150 compatibility
uniform vec3 cameraPosition;
uniform float ip_ShadowClipProbe;
void main() {
    vec3 worldPosition = gl_Vertex.xyz + cameraPosition;
    // Keep the light's near/far slab inside the cleared scene (Z=-5..8).
    // Natural Nether terrain beyond it must not shadow the owned receiver.
    gl_Position = vec4(worldPosition.x / 16.0, (worldPosition.y - 82.0) / 16.0, -worldPosition.z / 4.0, 1.0);
    // Development-only upload reflects the live GL clip bit immediately before each draw.
    gl_ClipDistance[0] = ip_ShadowClipProbe;
}

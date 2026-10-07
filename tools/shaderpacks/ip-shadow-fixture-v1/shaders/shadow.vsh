#version 150 compatibility
uniform vec3 cameraPosition;
void main() {
    vec3 worldPosition = gl_Vertex.xyz + cameraPosition;
    gl_Position = vec4(worldPosition.x / 16.0, (worldPosition.y - 82.0) / 16.0, -worldPosition.z / 32.0, 1.0);
    // Shadow programs may write unrelated clip values. The world clipping bit must be off.
    gl_ClipDistance[0] = -1.0;
}

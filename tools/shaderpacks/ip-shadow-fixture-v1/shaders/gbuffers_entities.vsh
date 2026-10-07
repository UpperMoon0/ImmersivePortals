#version 150 compatibility
uniform vec3 cameraPosition;
out vec3 worldPosition;
void main() {
    worldPosition = gl_Vertex.xyz + cameraPosition;
    gl_Position = ftransform();
}

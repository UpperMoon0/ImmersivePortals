#version 150 compatibility
out vec2 texcoord;
out vec4 tint;
void main() {
    gl_Position = ftransform();
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    tint = gl_Color;
}

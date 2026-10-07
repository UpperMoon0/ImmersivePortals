#version 150 compatibility
uniform sampler2D gtexture;
in vec2 texcoord;
in vec4 tint;
/* RENDERTARGETS: 0 */
void main() {
    vec4 sampleColor = texture(gtexture, texcoord);
    if (sampleColor.a < 0.1) discard;
    gl_FragData[0] = sampleColor * tint;
}

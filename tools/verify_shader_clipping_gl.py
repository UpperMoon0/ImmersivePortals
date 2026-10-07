#!/usr/bin/env python3
"""Compile the actual clipping transformer, then test synthetic GLSL pixels in Mesa EGL.

Requires Java 21 with jdk.compiler and Linux EGL/OpenGL libraries. No Minecraft
or shaderpack integration is exercised; use tools/verify.py for that acceptance.
Run: python tools/verify_shader_clipping_gl.py
"""
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
JAVA_SOURCE = r'''
import java.nio.file.*;
import qouteall.imm_ptl.core.render.ShaderClippingTransformation;
class GenerateShaders {
 public static void main(String[] a) throws Exception {
  var dir=Path.of(a[0]); Files.createDirectories(dir);
  String v = "#version 400 core\nuniform mat4 iris_ProjectionMatrix;\nout gl_PerVertex{vec4 gl_Position;};\nvoid main(){ vec2 p[3]=vec2[3](vec2(-0.9,-0.9),vec2(0.9,-0.9),vec2(0,0.9)); gl_Position=iris_ProjectionMatrix*vec4(p[gl_VertexID],0,1); if(gl_VertexID==0)return;}\nvoid afterMain(){}\n";
  String g = "#version 400 core\nlayout(triangles) in; layout(triangle_strip,max_vertices=3) out;\nin gl_PerVertex{vec4 gl_Position;} gl_in[]; out gl_PerVertex{vec4 gl_Position;};\nvoid main(){ for(int i=0;i<3;i++){gl_Position=gl_in[i].gl_Position; EmitVertex();}EndPrimitive();}\n";
  String t = "#version 400 core\nlayout(triangles,equal_spacing,cw) in;\nin gl_PerVertex{vec4 gl_Position;} gl_in[];\nvoid main(){gl_Position=gl_TessCoord.x*gl_in[0].gl_Position+gl_TessCoord.y*gl_in[1].gl_Position+gl_TessCoord.z*gl_in[2].gl_Position;}\n";
  Files.writeString(dir.resolve("vertex.vert"),ShaderClippingTransformation.transform(v,ShaderClippingTransformation.Stage.VERTEX,"iris_ProjectionMatrix"));
  Files.writeString(dir.resolve("pass.vert"),v);
  Files.writeString(dir.resolve("geometry.geom"),ShaderClippingTransformation.transform(g,ShaderClippingTransformation.Stage.GEOMETRY,"iris_ProjectionMatrix"));
  Files.writeString(dir.resolve("tessellation.tese"),ShaderClippingTransformation.transform(t,ShaderClippingTransformation.Stage.TESS_EVAL,"iris_ProjectionMatrix"));
  Files.writeString(dir.resolve("tessellation.tesc"),"#version 400 core\nlayout(vertices=3) out; in gl_PerVertex{vec4 gl_Position;} gl_in[]; out gl_PerVertex{vec4 gl_Position;} gl_out[]; void main(){gl_out[gl_InvocationID].gl_Position=gl_in[gl_InvocationID].gl_Position; if(gl_InvocationID==0){gl_TessLevelInner[0]=1; gl_TessLevelOuter[0]=1;gl_TessLevelOuter[1]=1;gl_TessLevelOuter[2]=1;}}\n");
  Files.writeString(dir.resolve("color.frag"),"#version 400 core\nout vec4 color; void main(){color=vec4(1,0,0,1);}\n");
 }
}
'''

# Keep products outside the checkout. Java's compiler module also works on
# images that have Java but do not install a javac command on PATH.
workspace = tempfile.TemporaryDirectory(prefix="immptl-clipping-gl-")
work = Path(workspace.name)
os.environ.setdefault("MESA_SHADER_CACHE_DIR", str(work / "mesa-cache"))
java = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java") if "JAVA_HOME" in os.environ else shutil.which("java")
if not java:
    raise SystemExit("Java 21 is required for the clipping GL regression")
(work / "GenerateShaders.java").write_text(JAVA_SOURCE)
subprocess.run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", str(work),
    str(REPO / "src/main/java/qouteall/imm_ptl/core/render/ShaderClippingTransformation.java"),
    str(work / "GenerateShaders.java")], check=True)
subprocess.run([java, "-cp", str(work), "GenerateShaders", str(work / "glsl")], check=True)

import ctypes as C
import json

# EGL surfaceless context, so this test does not need a window or a display server.
EGL = C.CDLL("libEGL.so.1")
GL = C.CDLL("libGL.so.1")
Int, UInt, Float, Pointer = C.c_int, C.c_uint, C.c_float, C.c_void_p


def function(library, name, result, *arguments):
    call = getattr(library, name)
    call.restype = result
    call.argtypes = arguments
    return call


get_proc = function(EGL, "eglGetProcAddress", Pointer, C.c_char_p)
platform_display = C.CFUNCTYPE(Pointer, UInt, Pointer, C.POINTER(Int))(
    get_proc(b"eglGetPlatformDisplayEXT")
)
display = platform_display(0x31DD, None, None)  # EGL_PLATFORM_SURFACELESS_MESA
assert display
assert function(EGL, "eglInitialize", UInt, Pointer, C.POINTER(Int), C.POINTER(Int))(
    display, None, None
)
assert function(EGL, "eglBindAPI", UInt, UInt)(0x30A2)  # EGL_OPENGL_API
attributes = (Int * 13)(
    0x3033, 1, 0x3040, 8, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038
)
config, count = Pointer(), Int()
assert function(EGL, "eglChooseConfig", UInt, Pointer, C.POINTER(Int),
                C.POINTER(Pointer), Int, C.POINTER(Int))(
    display, attributes, C.byref(config), 1, C.byref(count)
) and count.value
surface = function(EGL, "eglCreatePbufferSurface", Pointer, Pointer, Pointer, C.POINTER(Int))(
    display, config, (Int * 5)(0x3057, 64, 0x3056, 64, 0x3038)
)
context = function(EGL, "eglCreateContext", Pointer, Pointer, Pointer, Pointer, C.POINTER(Int))(
    display, config, None, (Int * 7)(0x3098, 4, 0x30FB, 5, 0x30FD, 1, 0x3038)
)
assert context and surface
assert function(EGL, "eglMakeCurrent", UInt, Pointer, Pointer, Pointer, Pointer)(
    display, surface, surface, context
)
get_string = function(GL, "glGetString", C.c_char_p, UInt)
print("GL", get_string(0x1F02), get_string(0x1F01))

create_shader = function(GL, "glCreateShader", UInt, UInt)
shader_source = function(GL, "glShaderSource", None, UInt, Int, C.POINTER(C.c_char_p), C.POINTER(Int))
compile_shader = function(GL, "glCompileShader", None, UInt)
shader_status = function(GL, "glGetShaderiv", None, UInt, UInt, C.POINTER(Int))
shader_log = function(GL, "glGetShaderInfoLog", None, UInt, Int, C.POINTER(Int), C.c_char_p)


def shader(path, kind):
    handle = create_shader(kind)
    source = C.c_char_p(path.read_bytes())
    shader_source(handle, 1, C.byref(source), None)
    compile_shader(handle)
    ok, log = Int(), C.create_string_buffer(8192)
    shader_status(handle, 0x8B81, C.byref(ok))
    shader_log(handle, len(log), None, log)
    assert ok.value, (path, log.value)
    return handle


create_program = function(GL, "glCreateProgram", UInt)
attach = function(GL, "glAttachShader", None, UInt, UInt)
link = function(GL, "glLinkProgram", None, UInt)
program_status = function(GL, "glGetProgramiv", None, UInt, UInt, C.POINTER(Int))
program_log = function(GL, "glGetProgramInfoLog", None, UInt, Int, C.POINTER(Int), C.c_char_p)
use = function(GL, "glUseProgram", None, UInt)
location = function(GL, "glGetUniformLocation", Int, UInt, C.c_char_p)
matrix = function(GL, "glUniformMatrix4fv", None, Int, Int, UInt, C.POINTER(Float))
vector = function(GL, "glUniform4f", None, Int, Float, Float, Float, Float)

vao = UInt()
function(GL, "glGenVertexArrays", None, Int, C.POINTER(UInt))(1, C.byref(vao))
function(GL, "glBindVertexArray", None, UInt)(vao)
function(GL, "glViewport", None, Int, Int, Int, Int)(0, 0, 64, 64)
function(GL, "glEnable", None, UInt)(0x3000)  # GL_CLIP_DISTANCE0
function(GL, "glClearColor", None, Float, Float, Float, Float)(0, 0, 0, 1)
clear = function(GL, "glClear", None, UInt)
draw = function(GL, "glDrawArrays", None, UInt, Int, Int)
read = function(GL, "glReadPixels", None, Int, Int, Int, Int, UInt, UInt, Pointer)
get_error = function(GL, "glGetError", UInt)
root = work / "glsl"
results = []

for label, stages in [
    ("vertex", [("vertex.vert", 0x8B31)]),
    ("geometry", [("pass.vert", 0x8B31), ("geometry.geom", 0x8DD9)]),
    ("tessellation", [("pass.vert", 0x8B31), ("tessellation.tesc", 0x8E88),
                      ("tessellation.tese", 0x8E87)]),
]:
    program = create_program()
    for filename, kind in stages + [("color.frag", 0x8B30)]:
        attach(program, shader(root / filename, kind))
    link(program)
    ok, log = Int(), C.create_string_buffer(8192)
    program_status(program, 0x8B82, C.byref(ok))
    program_log(program, len(log), None, log)
    assert ok.value, (label, log.value)
    use(program)
    matrix(location(program, b"iris_ProjectionMatrix"), 1, 0,
           (Float * 16)(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1))
    assert location(program, b"iportal_ClippingEquation") >= 0
    for plane in [(1, 0, 0, 0), (0, 0, 0, 1)]:
        vector(location(program, b"iportal_ClippingEquation"), *plane)
        clear(0x4000)
        draw(0xE if label == "tessellation" else 4, 0, 3)
        pixels = (C.c_ubyte * (64 * 64 * 4))()
        read(0, 0, 64, 64, 0x1908, 0x1401, pixels)
        assert get_error() == 0
        counts = [
            sum(pixels[(y * 64 + x) * 4] > 128
                for y in range(64) for x in range(start, start + 32))
            for start in (0, 32)
        ]
        assert counts[1] > 500, (label, plane, counts)
        assert (counts[0] == 0 if plane[0] else counts[0] > 500), (label, plane, counts)
        results.append(dict(stage=label, plane=plane, left=counts[0], right=counts[1]))
print(json.dumps(results, indent=2))

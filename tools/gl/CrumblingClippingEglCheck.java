import com.mojang.blaze3d.shaders.Program;
import me.shedaniel.cloth.clothconfig.shadowed.org.yaml.snakeyaml.Yaml;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.EGL;
import org.lwjgl.egl.EGL10;
import org.lwjgl.egl.EGL12;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import qouteall.imm_ptl.core.render.ShaderCodeTransformation;
import qouteall.imm_ptl.core.render.VanillaClippingPolicy;

import java.nio.file.Files;
import java.nio.file.Path;

/** Uses the production YAML transformer and a real GL driver; no Minecraft mixins are emulated. */
public class CrumblingClippingEglCheck {
    public static void main(String[] args) throws Exception {
        long display = org.lwjgl.system.JNI.callPPP(0x31DD, 0L, 0L,
            EGL10.eglGetProcAddress("eglGetPlatformDisplayEXT"));
        int[] major = new int[1], minor = new int[1];
        require(EGL10.eglInitialize(display, major, minor), "eglInitialize");
        EGL.createDisplayCapabilities(display, major[0], minor[0]);
        require(EGL12.eglBindAPI(0x30A2), "eglBindAPI");
        PointerBuffer configs = BufferUtils.createPointerBuffer(1);
        require(EGL10.eglChooseConfig(display,
            new int[]{0x3033, 1, 0x3040, 8, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3025, 24, 0x3038},
            configs, new int[1]), "eglChooseConfig");
        long surface = EGL10.eglCreatePbufferSurface(display, configs.get(0), new int[]{0x3057, 64, 0x3056, 64, 0x3038});
        long context = EGL10.eglCreateContext(display, configs.get(0), 0,
            new int[]{0x3098, 3, 0x30FB, 3, 0x30FD, 1, 0x3038});
        require(EGL10.eglMakeCurrent(display, surface, surface, context), "eglMakeCurrent");
        GL.createCapabilities();
        System.out.println(GL11.glGetString(GL11.GL_VERSION));
        System.out.println(GL11.glGetString(GL11.GL_RENDERER));

        var field = ShaderCodeTransformation.class.getDeclaredField("configs");
        field.setAccessible(true);
        Object previous = field.get(null);
        var loaded = new Yaml().loadAs(Files.readString(Path.of(args[0])), ShaderCodeTransformation.ConfigsObj.class);
        field.set(null, loaded.configs);
        String original = "#version 150\nin vec3 Position; uniform mat4 ModelViewMat; uniform mat4 ProjMat;\n"
            + "void main() { gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0); }";
        String transformed = ShaderCodeTransformation.transform(Program.Type.VERTEX, "rendertype_crumbling", original);
        require(!original.equals(transformed), "production YAML must transform rendertype_crumbling");
        require(ShaderCodeTransformation.shouldAddUniform("rendertype_crumbling"), "shader-instance uniform registration");
        int raw = program(original), clipped = program(transformed);
        int vao = GL30.glGenVertexArrays(), buffer = GL15.glGenBuffers();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0L);
        GL20.glEnableVertexAttribArray(0);
        GL11.glViewport(0, 0, 64, 64);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);

        draw(raw, false, -2, false, false);  // Reproduces the unpatched late overlay covering the aperture.
        draw(clipped, true, -2, false, true); // Same excluded geometry must disappear with the real YAML rule.
        draw(clipped, true, -6, false, false); // Retained geometry still draws.
        draw(clipped, true, -2, true, false); // Neutral plane does not erase ordinary outside-portal overlays.
        // Create's contraptions and large cog BER use chunk render types even though
        // their buffered vertices are camera-relative. The default ChunkOffset is zero.
        // Nonzero-offset cases also protect the existing chunk-local YAML convention.
        for (String name : new String[]{"rendertype_solid", "rendertype_cutout", "rendertype_cutout_mipped", "rendertype_translucent"}) {
            require(VanillaClippingPolicy.needsDrawScope(name), name + " must enter the late draw scope");
            require(ShaderCodeTransformation.shouldAddUniform(name), name + " uniform registration");
            String terrain = "#version 150\nin vec3 Position; uniform vec3 ChunkOffset;\n"
                + "uniform mat4 ModelViewMat; uniform mat4 ProjMat;\n"
                + "void main() { gl_Position = ProjMat * ModelViewMat * vec4(Position + ChunkOffset, 1.0); }";
            int terrainProgram = program(ShaderCodeTransformation.transform(Program.Type.VERTEX, name, terrain));
            draw(terrainProgram, false, -2, false, false); // Reproduces the deferred solid leak.
            draw(terrainProgram, true, -2, false, true);   // Same geometry is fully excluded.
            draw(terrainProgram, true, -6, false, false);  // Retained contraption/BER still draws.
            draw(terrainProgram, true, -2, true, false);   // Outside-portal geometry still draws.
            draw(terrainProgram, true, -6, 4, new Matrix4f(), false, true);
            draw(terrainProgram, true, 2, -8, new Matrix4f(), false, false);
            // Applying the plane after ModelView would incorrectly retain this vertex.
            draw(terrainProgram, true, -2, 0, new Matrix4f().translate(0, 0, -8), false, true);
            GL20.glDeleteProgram(terrainProgram);
            System.out.println("PASS buffered world shader=" + name);
        }
        GL20.glDeleteProgram(raw);
        GL20.glDeleteProgram(clipped);
        GL15.glDeleteBuffers(buffer);
        GL30.glDeleteVertexArrays(vao);
        field.set(null, previous);
        EGL10.eglMakeCurrent(display, 0, 0, 0);
        EGL10.eglDestroyContext(display, context);
        EGL10.eglDestroySurface(display, surface);
        EGL10.eglTerminate(display);
    }

    private static void draw(int program, boolean clipping, float z, boolean neutral, boolean expectBackground) {
        draw(program, clipping, z, 0, new Matrix4f(), neutral, expectBackground);
    }

    private static void draw(int program, boolean clipping, float z, float chunkOffsetZ,
                             Matrix4f modelView, boolean neutral, boolean expectBackground) {
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, new float[]{-20, -20, z, 20, -20, z, 0, 20, z}, GL15.GL_STATIC_DRAW);
        GL20.glUseProgram(program);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "ModelViewMat"), false, modelView.get(new float[16]));
        GL20.glUniform3f(GL20.glGetUniformLocation(program, "ChunkOffset"), 0, 0, chunkOffsetZ);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "ProjMat"), false,
            new Matrix4f().perspective((float) Math.toRadians(70), 1, 0.05f, 100).get(new float[16]));
        int location = GL20.glGetUniformLocation(program, "iportal_ClippingEquation");
        if (clipping) {
            require(location >= 0, "transformed program has clipping uniform");
            GL20.glUniform4f(location, 0, 0, neutral ? 0 : -1, neutral ? 1 : -4);
            GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
        }
        else GL11.glDisable(GL30.GL_CLIP_DISTANCE0);
        GL11.glClearColor(0, 1, 0, 1);
        GL11.glClearDepth(1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        var pixels = BufferUtils.createByteBuffer(16 * 16 * 4);
        GL11.glReadPixels(24, 24, 16, 16, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        int green = 0;
        for (int i = 0; i < 256; i++) {
            int r = pixels.get(i * 4) & 255, g = pixels.get(i * 4 + 1) & 255;
            if (g > 250 && r < 5) green++;
        }
        require(green == (expectBackground ? 256 : 0), "buffered world pixel oracle: green=" + green);
        var depths = BufferUtils.createFloatBuffer(16 * 16);
        GL11.glReadPixels(24, 24, 16, 16, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depths);
        for (int i = 0; i < 256; i++) {
            require(expectBackground ? depths.get(i) == 1f : depths.get(i) < 1f,
                "excluded geometry must not write depth; retained geometry must write depth");
        }
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "unexpected GL error");
        System.out.println("PASS clipping=" + clipping + " z=" + z + " offset=" + chunkOffsetZ
            + " neutral=" + neutral + " green=" + green + " depth=" + depths.get(0));
    }

    private static int program(String vertex) {
        int v = shader(GL20.GL_VERTEX_SHADER, vertex);
        int f = shader(GL20.GL_FRAGMENT_SHADER, "#version 150\nout vec4 color; void main(){color=vec4(0.05,0.04,0.01,1.0);}");
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, v);
        GL20.glAttachShader(p, f);
        GL20.glBindAttribLocation(p, 0, "Position");
        GL20.glLinkProgram(p);
        require(GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) != 0, GL20.glGetProgramInfoLog(p));
        GL20.glDeleteShader(v);
        GL20.glDeleteShader(f);
        return p;
    }

    private static int shader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        require(GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != 0, GL20.glGetShaderInfoLog(shader));
        return shader;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

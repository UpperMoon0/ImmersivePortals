import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.*;
import org.lwjgl.opengl.GL;
import qouteall.imm_ptl.core.gametest.sablee2e.PortalShadowOracle;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;

/** Runs owned shadow/receiver shaders and live-bit sentinel; does not exercise Minecraft shadow submission. */
public class ShadowFixtureEglCheck {
    public static void main(String[] args) throws Exception {
        String[] version = System.getProperty("ip.test.glVersion", "4.5").split("\\.");
        long display = org.lwjgl.system.JNI.callPPP(0x31DD, 0L, 0L, EGL10.eglGetProcAddress("eglGetPlatformDisplayEXT"));
        int[] major = new int[1], minor = new int[1];
        require(EGL10.eglInitialize(display, major, minor), "EGL initialization");
        EGL.createDisplayCapabilities(display, major[0], minor[0]);
        require(EGL12.eglBindAPI(0x30A2), "OpenGL API binding");
        PointerBuffer configs = BufferUtils.createPointerBuffer(1);
        int[] count = new int[1];
        require(EGL10.eglChooseConfig(display, new int[]{0x3033, 1, 0x3040, 8, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3038}, configs, count), "EGL config");
        long surface = EGL10.eglCreatePbufferSurface(display, configs.get(0), new int[]{0x3057, 64, 0x3056, 64, 0x3038});
        long context = EGL10.eglCreateContext(display, configs.get(0), 0,
            new int[]{0x3098, Integer.parseInt(version[0]), 0x30FB, Integer.parseInt(version[1]), 0x30FD, 1, 0x3038});
        require(EGL10.eglMakeCurrent(display, surface, surface, context), "EGL current context");
        GL.createCapabilities();
        System.out.println("GL=" + glGetString(GL_VERSION) + " renderer=" + glGetString(GL_RENDERER));
        int depth = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depth);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, 256, 256, 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0L);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        int framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
        glReadBuffer(GL_NONE); glDrawBuffer(GL_NONE);
        require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "complete depth framebuffer");
        glBindVertexArray(glGenVertexArrays());
        glBindBuffer(GL_ARRAY_BUFFER, glGenBuffers());
        glEnable(GL_DEPTH_TEST); glDepthMask(true); glDepthFunc(GL_LESS);
        glDisable(GL_CULL_FACE); glViewport(0, 0, 256, 256);

        String minimal = "#version 330 core\nin vec3 ipPosition; uniform float ip_ShadowClipProbe;"
            + "void main(){gl_Position=vec4(ipPosition.xy/16.0,0.09375,1);gl_ClipDistance[0]=ip_ShadowClipProbe;}";
        String fragment = "#version 330 core\nout vec4 color;void main(){color=vec4(1);}";
        int program = program(minimal, fragment);
        for (boolean enabled : new boolean[]{false, true}) {
            float actual = draw(program, -3, enabled, -1);
            // Diagnostic only: preserve the driver's observed disabled-negative behavior.
            System.out.println("OBSERVED minimal dynamic-negative: clip=" + enabled + " depth=" + actual);
            if (enabled) require(Math.abs(actual - 1) < 0.0001, "enabled negative clips geometry");
        }
        glDeleteProgram(program);

        Path pack = Path.of(args[0]);
        // Adapt only compatibility attribute/output bindings, leaving the fixture's math unchanged.
        String vertex = Files.readString(pack.resolve("shadow.vsh"))
            .replace("#version 150 compatibility", "#version 330 core\nin vec4 ipPosition;")
            .replace("gl_Vertex", "ipPosition");
        fragment = Files.readString(pack.resolve("shadow.fsh"))
            .replace("#version 150 compatibility", "#version 330 core\nout vec4 ipColor;")
            .replace("gl_FragData[0]", "ipColor");
        program = program(vertex, fragment);
        // Exercise the pack's actual depth-to-receiver-color decision as well.
        int receiverProgram = program("#version 330 core\nout vec3 worldPosition; void main(){"
            + "worldPosition=vec3(0,82,-3);vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);"
            + "gl_Position=vec4(p*2.0-1.0,0,1);}",
            Files.readString(pack.resolve("gbuffers_terrain.fsh"))
                .replace("#version 150 compatibility", "#version 330 core\nout vec4 ipColor;")
                .replace("gl_FragData[0]", "ipColor"));
        int color = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, color);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 5, 5, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
        int receiverFramebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, receiverFramebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
        glReadBuffer(GL_COLOR_ATTACHMENT0); glDrawBuffer(GL_COLOR_ATTACHMENT0);
        require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "complete receiver framebuffer");
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        for (int worldZ : new int[]{-3, 2}) for (boolean enabled : new boolean[]{false, true}) {
            float actual = draw(program, worldZ, enabled, PortalShadowOracle.clipProbe(enabled));
            float expected = enabled ? 1 : (float) (worldZ == -3 ? PortalShadowOracle.LIT_DEPTH : PortalShadowOracle.CASTER_DEPTH);
            require(Math.abs(actual - expected) < 0.0001, "owned fixture exact depth, z=" + worldZ + " clip=" + enabled);
            System.out.println("PASS owned fixture: worldZ=" + worldZ + " clip=" + enabled + " depth=" + actual);
        }
        for (int worldZ : new int[]{-30, -6, 9, 30}) {
            float actual = draw(program, worldZ, false, PortalShadowOracle.clipProbe(false));
            require(Math.abs(actual - 1) < 0.0001, "terrain beyond cleared scene cannot enter shadow map, z=" + worldZ);
            System.out.println("PASS outside scene: worldZ=" + worldZ + " depth=" + actual);
        }
        for (boolean caster : new boolean[]{false, true}) for (boolean enabled : new boolean[]{false, true}) {
            int[] scene = caster ? new int[]{30, -3, 2, -30} : new int[]{30, -3, -30};
            float actual = drawScene(program, enabled, PortalShadowOracle.clipProbe(enabled), scene);
            float expected = enabled ? 1 : (float) (caster ? PortalShadowOracle.CASTER_DEPTH : PortalShadowOracle.LIT_DEPTH);
            require(Math.abs(actual - expected) < 0.0001, "outside terrain cannot contaminate owned scene");
            boolean shadowed = caster && !enabled;
            checkReceiver(receiverProgram, receiverFramebuffer, depth, shadowed);
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glViewport(0, 0, 256, 256); glEnable(GL_DEPTH_TEST);
            System.out.println("PASS isolated scene: caster=" + caster + " clip=" + enabled + " depth=" + actual
                + " receiver=" + (shadowed ? "blue" : "green"));
        }
        glDeleteProgram(receiverProgram);
        glDeleteProgram(program);
        require(glGetError() == GL_NO_ERROR, "no GL errors");
    }

    private static float draw(int program, int worldZ, boolean enabled, float probe) {
        return drawScene(program, enabled, probe, worldZ);
    }

    private static float drawScene(int program, boolean enabled, float probe, int... worldZs) {
        glUseProgram(program);
        glUniform3f(glGetUniformLocation(program, "cameraPosition"), 0, 82, 4);
        int location = glGetUniformLocation(program, "ip_ShadowClipProbe");
        require(location >= 0, "live clip probe uniform is active");
        int position = glGetAttribLocation(program, "ipPosition");
        require(position >= 0, "position attribute is active");
        glEnableVertexAttribArray(position);
        glVertexAttribPointer(position, 3, GL_FLOAT, false, 12, 0L);
        if (enabled) glEnable(GL_CLIP_DISTANCE0); else glDisable(GL_CLIP_DISTANCE0);
        require(glIsEnabled(GL_CLIP_DISTANCE0) == enabled, "actual clip bit matches request");
        glUniform1f(location, probe);
        glClearDepth(1); glClear(GL_DEPTH_BUFFER_BIT);
        for (int worldZ : worldZs) {
            glBufferData(GL_ARRAY_BUFFER, new float[]{-8, -8, worldZ - 4, 8, -8, worldZ - 4, 0, 5, worldZ - 4}, GL_STATIC_DRAW);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }
        float[] samples = new float[25];
        glReadPixels(126, 126, 5, 5, GL_DEPTH_COMPONENT, GL_FLOAT, samples);
        for (float sample : samples) require(Math.abs(sample - samples[12]) < 0.0001, "consistent interior depth pixels");
        require(glGetError() == GL_NO_ERROR, "no GL errors during draw");
        glDisableVertexAttribArray(position);
        return samples[12];
    }
    private static void checkReceiver(int program, int framebuffer, int shadowTexture, boolean shadowed) {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glViewport(0, 0, 5, 5);
        glDisable(GL_DEPTH_TEST); glDisable(GL_CLIP_DISTANCE0);
        glUseProgram(program);
        glBindTexture(GL_TEXTURE_2D, shadowTexture);
        glUniform1i(glGetUniformLocation(program, "shadowtex0"), 0);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        float[] color = new float[4];
        glReadPixels(2, 2, 1, 1, GL_RGBA, GL_FLOAT, color);
        float[] expected = shadowed ? new float[]{0.05f, 0.15f, 0.85f} : new float[]{0.1f, 0.85f, 0.1f};
        for (int i = 0; i < 3; i++) require(Math.abs(color[i] - expected[i]) < 0.005, "actual receiver fragment color");
    }
    private static int program(String vertex, String fragment) {
        int vs = shader(GL_VERTEX_SHADER, vertex), fs = shader(GL_FRAGMENT_SHADER, fragment);
        int program = glCreateProgram(); glAttachShader(program, vs); glAttachShader(program, fs); glLinkProgram(program);
        require(glGetProgrami(program, GL_LINK_STATUS) != 0, glGetProgramInfoLog(program));
        glDeleteShader(vs); glDeleteShader(fs);
        return program;
    }
    private static int shader(int type, String source) {
        int shader = glCreateShader(type); glShaderSource(shader, source); glCompileShader(shader);
        require(glGetShaderi(shader, GL_COMPILE_STATUS) != 0, glGetShaderInfoLog(shader));
        return shader;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

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

/** Runs the owned projection and live-bit sentinel; does not exercise Minecraft shadow submission. */
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
        for (int worldZ : new int[]{-3, 2}) for (boolean enabled : new boolean[]{false, true}) {
            float actual = draw(program, worldZ, enabled, PortalShadowOracle.clipProbe(enabled));
            float expected = enabled ? 1 : 0.5f - worldZ / 64f;
            require(Math.abs(actual - expected) < 0.0001, "owned fixture exact depth, z=" + worldZ + " clip=" + enabled);
            System.out.println("PASS owned fixture: worldZ=" + worldZ + " clip=" + enabled + " depth=" + actual);
        }
        glDeleteProgram(program);
        require(glGetError() == GL_NO_ERROR, "no GL errors");
    }

    private static float draw(int program, int worldZ, boolean enabled, float probe) {
        glUseProgram(program);
        glUniform3f(glGetUniformLocation(program, "cameraPosition"), 0, 82, 4);
        int location = glGetUniformLocation(program, "ip_ShadowClipProbe");
        require(location >= 0, "live clip probe uniform is active");
        glBufferData(GL_ARRAY_BUFFER, new float[]{-8, -8, worldZ - 4, 8, -8, worldZ - 4, 0, 5, worldZ - 4}, GL_STATIC_DRAW);
        int position = glGetAttribLocation(program, "ipPosition");
        require(position >= 0, "position attribute is active");
        glEnableVertexAttribArray(position);
        glVertexAttribPointer(position, 3, GL_FLOAT, false, 12, 0L);
        if (enabled) glEnable(GL_CLIP_DISTANCE0); else glDisable(GL_CLIP_DISTANCE0);
        require(glIsEnabled(GL_CLIP_DISTANCE0) == enabled, "actual clip bit matches request");
        glUniform1f(location, probe);
        glClearDepth(1); glClear(GL_DEPTH_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        float[] samples = new float[25];
        glReadPixels(126, 126, 5, 5, GL_DEPTH_COMPONENT, GL_FLOAT, samples);
        for (float sample : samples) require(Math.abs(sample - samples[12]) < 0.0001, "consistent interior depth pixels");
        require(glGetError() == GL_NO_ERROR, "no GL errors during draw");
        glDisableVertexAttribArray(position);
        return samples[12];
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

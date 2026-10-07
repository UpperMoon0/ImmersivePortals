import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.EGL;
import org.lwjgl.egl.EGL10;
import org.lwjgl.egl.EGL12;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.KHRDebug;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisDebugLabelPolicy;

/** Actual-driver reproduction of Iris's depthless RenderTarget debug-label error. */
public class IrisDebugLabelsEglCheck {
    public static void main(String[] args) {
        long display = org.lwjgl.system.JNI.callPPP(0x31DD, 0L, 0L,
            EGL10.eglGetProcAddress("eglGetPlatformDisplayEXT"));
        int[] major = new int[1], minor = new int[1];
        require(EGL10.eglInitialize(display, major, minor), "eglInitialize");
        EGL.createDisplayCapabilities(display, major[0], minor[0]);
        require(EGL12.eglBindAPI(0x30A2), "eglBindAPI");
        PointerBuffer configs = BufferUtils.createPointerBuffer(1);
        int[] count = new int[1];
        require(EGL10.eglChooseConfig(display,
            new int[]{0x3033, 1, 0x3040, 8, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3038},
            configs, count), "eglChooseConfig");
        long surface = EGL10.eglCreatePbufferSurface(display, configs.get(0),
            new int[]{0x3057, 1, 0x3056, 1, 0x3038});
        long context = EGL10.eglCreateContext(display, configs.get(0), 0,
            new int[]{0x3098, 4, 0x30FB, 5, 0x30FD, 1, 0x3038});
        require(EGL10.eglMakeCurrent(display, surface, surface, context), "eglMakeCurrent");
        GL.createCapabilities();
        System.out.println(GL11.glGetString(GL11.GL_VERSION));
        System.out.println(GL11.glGetString(GL11.GL_RENDERER));
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "clean initial context");

        // Reproduce the exact unguarded upstream call, then consume that expected
        // error only in this diagnostic. No production code drains GL errors.
        KHRDebug.glObjectLabel(GL11.GL_TEXTURE, -1, "Main depth texture");
        require(GL11.glGetError() == GL11.GL_INVALID_VALUE, "unguarded absent depth produces INVALID_VALUE");
        System.out.println("PASS unguarded missing-depth label reproduces GL_INVALID_VALUE");

        guardedLabel(GL11.GL_TEXTURE, -1, "Main depth texture");
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "guarded absent depth must not call GL");
        System.out.println("PASS production policy avoids absent-depth GL error");

        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        guardedLabel(GL11.GL_TEXTURE, texture, "Main depth texture");
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "valid depth label");
        require("Main depth texture".equals(KHRDebug.glGetObjectLabel(GL11.GL_TEXTURE, texture, 128)),
            "valid depth label must actually reach driver");
        GL11.glDeleteTextures(texture);
        System.out.println("PASS valid depth labels remain visible on actual texture");

        guardedLabel(GL11.GL_TEXTURE, -1, "Main color texture");
        require(GL11.glGetError() == GL11.GL_INVALID_VALUE, "other invalid label must remain visible");
        guardedLabel(KHRDebug.GL_BUFFER, -1, "Main depth texture");
        require(GL11.glGetError() == GL11.GL_INVALID_VALUE, "other invalid object type must remain visible");
        System.out.println("PASS other invalid object labels still reach GL and fail");

        EGL10.eglMakeCurrent(display, 0, 0, 0);
        EGL10.eglDestroyContext(display, context);
        EGL10.eglDestroySurface(display, surface);
        EGL10.eglTerminate(display);
    }

    private static void guardedLabel(int identifier, int object, String label) {
        if (IrisDebugLabelPolicy.shouldLabel(identifier, object, label)) {
            KHRDebug.glObjectLabel(identifier, object, label);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package qouteall.imm_ptl.core.gametest.sablee2e;

import org.joml.Matrix4fc;

import java.lang.reflect.Method;

/** Development-only ABI bridge: Iris returns Matrix4fc; pinned NeOculus returns Matrix4f. */
public final class PortalCapturedMatrices {
    private final Object state;
    private final Method modelView;
    private final Method projection;

    private PortalCapturedMatrices(Object state, Method modelView, Method projection) {
        this.state = state;
        this.modelView = modelView;
        this.projection = projection;
    }

    private static final class RuntimeBinding {
        private static final PortalCapturedMatrices INSTANCE = load();

        private static PortalCapturedMatrices load() {
            try {
                return bind(Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState"));
            }
            catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot bind shader diagnostic captured matrices", e);
            }
        }
    }

    static PortalCapturedMatrices bind(Class<?> type) throws ReflectiveOperationException {
        Object state = type.getField("INSTANCE").get(null);
        Method modelView = type.getMethod("getGbufferModelView");
        Method projection = type.getMethod("getGbufferProjection");
        for (Method method : new Method[]{modelView, projection}) {
            if (!Matrix4fc.class.isAssignableFrom(method.getReturnType())) {
                throw new IllegalStateException("Unsupported captured matrix ABI: " + method);
            }
        }
        return new PortalCapturedMatrices(state, modelView, projection);
    }

    public static float[] modelView() {
        return RuntimeBinding.INSTANCE.readModelView();
    }

    public static float[] projection() {
        return RuntimeBinding.INSTANCE.readProjection();
    }

    float[] readModelView() {
        return read(modelView);
    }

    float[] readProjection() {
        return read(projection);
    }

    private float[] read(Method method) {
        try {
            Matrix4fc matrix = (Matrix4fc) method.invoke(state);
            return matrix == null ? null : matrix.get(new float[16]);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read shader diagnostic matrix " + method.getName(), e);
        }
    }
}

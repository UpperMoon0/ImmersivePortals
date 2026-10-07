package qouteall.imm_ptl.core.compat;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.mixin.neoculus.MixinNeOculusShaderTransformer;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NeOculusTerrainClippingTest {
    private enum Stage { VERTEX, GEOMETRY, TESS_EVAL, FRAGMENT }
    private static final String VERTEX = "#version 330 core\nuniform mat4 iris_ProjectionMatrix;\n"
        + "void main(){gl_Position=iris_ProjectionMatrix*vec4(1.0,2.0,3.0,1.0);}";

    @Test
    void actualNeOculusReturnHookPatchesTerrainAndWaterWithoutPoisoningItsCache() throws Exception {
        boolean previous = IPGlobal.enableClippingMechanism;
        try {
            IPGlobal.enableClippingMechanism = true;
            var cache = new EnumMap<Stage, String>(Stage.class);
            cache.put(Stage.VERTEX, VERTEX);
            cache.put(Stage.FRAGMENT, "void main(){}");
            for (String name : new String[]{"gbuffers_terrain", "gbuffers_terrain_solid", "gbuffers_terrain_cutout", "gbuffers_water"}) {
                var transformed = hook(name, cache);
                assertNotSame(cache, transformed);
                assertTrue(transformed.get(Stage.VERTEX).contains("iportal_ClippingEquation"));
                assertTrue(transformed.get(Stage.VERTEX).contains("inverse(iris_ProjectionMatrix)"));
                assertTrue(transformed.get(Stage.VERTEX).contains("gl_ClipDistance[0]"));
                assertEquals(VERTEX, cache.get(Stage.VERTEX));
            }
            assertSame(cache, hook("shadow", cache));
            assertSame(cache, hook("composite", cache));
            IPGlobal.enableClippingMechanism = false;
            assertSame(cache, hook("gbuffers_terrain", cache));
        } finally {
            IPGlobal.enableClippingMechanism = previous;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Stage, String> hook(String name, Map<Stage, String> source) throws Exception {
        var callback = new CallbackInfoReturnable<Map<Stage, String>>("transform", true, source);
        var hook = MixinNeOculusShaderTransformer.class.getDeclaredMethod("ip_afterTransform", CallbackInfoReturnable.class, String.class);
        hook.setAccessible(true);
        hook.invoke(null, callback, name);
        return callback.getReturnValue();
    }
}

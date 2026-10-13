package qouteall.imm_ptl.core.compat.mixin.sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable.SableSectionRenderOwner;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Dedicated ship sections have no terrain-graph membership, including during disposal. */
class SableTerrainNotificationTest {
    private static final String MIXIN="qouteall/imm_ptl/core/compat/mixin/sable/MixinRenderSection_SableExteriorNeighbors";
    private static final String SECTION="net/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection";
    private static final String SABLE="dev/ryanhcode/sable/";

    @Test void ordinarySectionAlwaysPreservesTheOriginalCallback() throws Exception {
        var section=new MixinRenderSection_SableExteriorNeighbors() {};
        AtomicInteger calls=new AtomicInteger();
        notify(section,calls);
        section.ip_setSableRenderOwner(null);
        reset(section);
        notify(section,calls);
        assertEquals(2,calls.get(),"No owner stamp means ordinary terrain, including after reset");
    }

    @Test void dedicatedProvenanceSurvivesResetCloseAndClearedOwnership() throws Exception {
        var section=new MixinRenderSection_SableExteriorNeighbors() {};
        // This test exercises only ownership publication/closure, never body or world access.
        var owner=new SableSectionRenderOwner(null);
        section.ip_setSableRenderOwner(owner);
        AtomicInteger calls=new AtomicInteger();
        notify(section,calls);
        reset(section); // releaseBuffers calls reset before Sable close calls setCompiled(EMPTY).
        var ownerField=MixinRenderSection_SableExteriorNeighbors.class.getDeclaredField("ip_sableOwner");
        ownerField.setAccessible(true);
        assertNull(ownerField.get(section),"Readiness ownership must still be revoked by reset");
        owner.close();
        notify(section,calls);
        section.ip_setSableRenderOwner(null);
        reset(section);
        notify(section,calls);
        assertEquals(0,calls.get(),"Retired ship sections must never notify a disposed terrain graph");
        var ordinary=new MixinRenderSection_SableExteriorNeighbors() {};
        notify(ordinary,calls);
        assertEquals(1,calls.get(),"Ship provenance must not leak to other section objects");
    }

    @Test void wrapperTargetsOnlyTheTerrainNotificationWithRequiredMatch() throws Exception {
        var hook=method(MIXIN,"ip_notifyOnlyTerrainSections");
        var wrap=hook.visibleAnnotations.stream()
            .filter(a->a.desc.endsWith("/WrapOperation;")).findFirst().orElseThrow();
        assertEquals(1,value(wrap,"require"));
        assertEquals(List.of("setCompiled"),value(wrap,"method"));
        Object atValue=value(wrap,"at");
        List<?> targets=atValue instanceof List<?> list ? list : List.of(atValue);
        assertEquals(1,targets.size());
        var at=(AnnotationNode)targets.getFirst();
        assertEquals("INVOKE",value(at,"value"));
        assertEquals("Lnet/minecraft/client/renderer/LevelRenderer;addRecentlyCompiledSection(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;)V",
            value(at,"target"));
        assertEquals(1,calls(hook,"call"));
        assertEquals(0,calls(hook,"setCompiled"));
        assertEquals(0,calls(hook,"setDirty"));
        assertEquals(0,calls(hook,"cancel"));
    }

    @Test void compiledAssignmentAndCancelCounterResetPrecedeTheSingleNotification() throws Exception {
        var compiled=method(SECTION,"setCompiled");
        int notification=callIndex(compiled,"addRecentlyCompiledSection");
        assertEquals(1,calls(compiled,"addRecentlyCompiledSection"));
        assertTrue(notification>=0);
        assertTrue(callIndex(compiled,"java/util/concurrent/atomic/AtomicReference","set")<notification);
        assertTrue(callIndex(compiled,"java/util/concurrent/atomic/AtomicReference","set")>=0);
        assertTrue(callIndex(compiled,"java/util/concurrent/atomic/AtomicInteger","set")<notification);
        assertTrue(callIndex(compiled,"java/util/concurrent/atomic/AtomicInteger","set")>=0);
    }

    @Test void actualSableSectionConstructionIsStampedBeforeCompilationCanNotify() throws Exception {
        assertEquals(0,calls(method(SECTION,"<init>"),"setCompiled"));
        assertEquals(0,calls(method(SECTION,"reset"),"setCompiled"));
        var resize=method(SABLE+"sublevel/render/vanilla/VanillaChunkedSubLevelRenderData","resize");
        assertTrue(callIndex(resize,SECTION,"<init>")>=0,"Sable allocates independent sections");
        assertEquals(0,calls(resize,"compileSections"));
        assertEquals(0,calls(resize,"rebuildSectionAsync"));
        assertEquals(0,calls(resize,"rebuildSectionSync"));
        var close=method(SABLE+"sublevel/render/vanilla/VanillaChunkedSubLevelRenderData","close");
        assertBefore(close,"releaseBuffers","setCompiled");
        var stamp=method("qouteall/imm_ptl/core/compat/mixin/sable/MixinVanillaChunkedSubLevelRenderData_SableSectionOwnership","ip_ownSections");
        var inject=stamp.visibleAnnotations.stream().filter(a->a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        assertEquals(List.of("resize"),value(inject,"method"));
        assertTrue(((List<?>)value(inject,"at")).stream()
            .anyMatch(a->a instanceof AnnotationNode node && "RETURN".equals(value(node,"value"))));
        assertTrue(callIndex(stamp,"refresh")>=0);
    }

    @Test void runtimeRegressionRetainsStoppedProbeVanillaRespawnAndNextStackCase() throws Exception {
        String fixture="qouteall/imm_ptl/core/gametest/sablee2e/SableShipFlightTest";
        assertTrue(callIndex(method(fixture,"stoppedAnimationProbe"),"finishCase")>=0);
        assertBefore(method(fixture,"finishCase"),"cleanup","setup");
        var cleanup=method(fixture,"cleanup");
        assertTrue(callIndex(cleanup,"net/minecraft/server/level/ServerPlayer","teleportTo")>=0,
            "Keep the real vanilla dimension reset that exposed the disposed-renderer crash");
    }

    private static void notify(MixinRenderSection_SableExteriorNeighbors section,AtomicInteger calls) throws Exception {
        var hook=MixinRenderSection_SableExteriorNeighbors.class.getDeclaredMethod("ip_notifyOnlyTerrainSections",
            LevelRenderer.class,SectionRenderDispatcher.RenderSection.class,Operation.class);
        hook.setAccessible(true);
        Operation<Void> original=arguments->{
            assertArrayEquals(new Object[] {null,null},arguments,"Pass through the exact receiver and section");
            calls.incrementAndGet(); return null;
        };
        hook.invoke(section,null,null,original);
    }
    private static void reset(MixinRenderSection_SableExteriorNeighbors section) throws Exception {
        var reset=MixinRenderSection_SableExteriorNeighbors.class.getDeclaredMethod("ip_forgetOwnerOnReset",CallbackInfo.class);
        reset.setAccessible(true); reset.invoke(section,new CallbackInfo("reset",false));
    }
    private static Object value(AnnotationNode node,String key) {
        int index=node.values.indexOf(key);
        assertTrue(index>=0,"Missing annotation property "+key);
        return node.values.get(index+1);
    }
    private static int calls(MethodNode method,String name) {
        int count=0;
        for (var instruction:method.instructions)
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)) count++;
        return count;
    }
    private static int callIndex(MethodNode method,String name) { return callIndex(method,null,name); }
    private static int callIndex(MethodNode method,String owner,String name) {
        for (int i=0;i<method.instructions.size();i++)
            if (method.instructions.get(i) instanceof MethodInsnNode call && call.name.equals(name)
                && (owner==null || owner.equals(call.owner))) return i;
        return -1;
    }
    private static void assertBefore(MethodNode method,String first,String second) {
        int a=callIndex(method,first),b=callIndex(method,second);
        assertTrue(a>=0 && b>a,first+" must precede "+second);
    }
    private static MethodNode method(String resource,String name) throws Exception {
        try (InputStream stream=SableTerrainNotificationTest.class.getClassLoader().getResourceAsStream(resource+".class")) {
            assertNotNull(stream,"Missing class "+resource);
            var node=new org.objectweb.asm.tree.ClassNode(); new ClassReader(stream).accept(node,0);
            return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();
        }
    }
}

package qouteall.imm_ptl.core.compat.mixin.sable;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Pins lifecycle assumptions behind the immutable, asynchronously read section owner. */
class SableSectionOwnershipContractTest {
    private static final String SABLE="dev/ryanhcode/sable/";
    private static final String COMPAT="qouteall/imm_ptl/core/compat/";

    @Test void finalizationRecreatesRenderDataAfterMarkingTheBodyReady() throws Exception {
        var handle=method(SABLE+"network/packets/tcp/ClientboundFinalizeSubLevelPacket","handle");
        assertBefore(handle,"setFinalized","updateRenderData");
        var update=method(SABLE+"sublevel/ClientSubLevel","updateRenderData");
        assertBefore(update,"close","createRenderData");
    }

    @Test void boundsChangesResizeAndRemovalClosesOwnedData() throws Exception {
        var handle=method(SABLE+"network/packets/tcp/ClientboundChangeBoundsSubLevelPacket","handle");
        assertBefore(handle,"setBoundingBox","onPlotBoundsChanged");
        var bounds=method(SABLE+"sublevel/ClientSubLevel","onPlotBoundsChanged");
        int resize=callIndex(bounds,"resize"),store=-1;
        for (int i=0;i<bounds.instructions.size();i++) {
            if (bounds.instructions.get(i) instanceof FieldInsnNode field
                && field.getOpcode()==Opcodes.PUTFIELD && field.name.equals("renderData")) store=i;
        }
        assertTrue(resize>=0 && store>resize,"New data can compile before the body's field assignment");
        assertTrue(callIndex(method(SABLE+"sublevel/ClientSubLevel","onRemove"),"close")>=0);
    }

    @Test void singleToChunkedTransitionCanCompileBeforeReturningItsNewData() throws Exception {
        var resize=method(SABLE+"sublevel/render/dispatcher/VanillaSubLevelRenderDispatcher","resize");
        assertBefore(resize,"createRenderData","compileSections");
    }

    @Test void exteriorExceptionNeverRequestsARebuildOrBypassesOrdinarySections() throws Exception {
        var hook=method(COMPAT+"mixin/sable/MixinRenderSection_SableExteriorNeighbors","ip_acceptFinalizedExteriorAir");
        assertBefore(hook,"getReturnValue","canUseExteriorAir");
        assertBefore(hook,"canUseExteriorAir","setReturnValue");
        for (var instruction:hook.instructions) if (instruction instanceof MethodInsnNode call) {
            assertFalse(call.name.contains("rebuild") || call.name.equals("setDirty"));
        }
        var reset=method(COMPAT+"mixin/sable/MixinRenderSection_SableExteriorNeighbors","ip_forgetOwnerOnReset");
        assertTrue(java.util.stream.StreamSupport.stream(reset.instructions.spliterator(),false)
            .anyMatch(insn->insn instanceof FieldInsnNode field && field.name.equals("ip_sableOwner")
                && field.getOpcode()==Opcodes.PUTFIELD));
    }

    @Test void mutableStateIsReadOnlyDuringPublicationAndEpochGuardsAsyncReads() throws Exception {
        var owner=read(COMPAT+"sable/SableSectionRenderOwner");
        var async=owner.methods.stream().filter(m->m.name.equals("canUseExteriorAir")).findFirst().orElseThrow();
        assertTrue(callIndex(async,"test")>=0);
        for (var candidate:owner.methods) if (candidate.name.contains("canUseExteriorAir")) {
            for (var instruction:candidate.instructions) if (instruction instanceof MethodInsnNode call) {
                assertFalse(java.util.Set.of("getBoundingBox","getSubLevel","isRemoved","isFinalized","getOrigin").contains(call.name));
            }
        }
        var refresh=method(COMPAT+"sable/SableSectionRenderOwner","refresh");
        assertBefore(refresh,"getBoundingBox","publish");
        assertTrue(callIndex(method(COMPAT+"sable/SableSectionRenderOwner","close"),"close")>=0);
    }

    @Test void cleanupHooksAreOptionalSableMixinsAndInvalidateAtHead() throws Exception {
        String cleanup="MixinClientWorldLoader_SableRenderOwnership";
        var owner=read(COMPAT+"mixin/sable/"+cleanup);
        for (var method:owner.methods) if (method.name.startsWith("ip_closeOwners")) {
            var inject=method.visibleAnnotations.stream().filter(a->a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
            Object at=inject.values.get(inject.values.indexOf("at")+1);
            assertInstanceOf(java.util.List.class,at);
            assertTrue(((java.util.List<?>)at).stream().anyMatch(value->value instanceof org.objectweb.asm.tree.AnnotationNode node
                && node.values.contains("HEAD")),"Cleanup must revoke eligibility before disposal");
        }
        try (InputStream stream=getClass().getClassLoader().getResourceAsStream("imm_ptl_compat.mixins.json")) {
            assertNotNull(stream);
            assertTrue(new String(stream.readAllBytes(),StandardCharsets.UTF_8).contains("sable."+cleanup));
        }
        var plugin=method(COMPAT+"IPCompatMixinPlugin","shouldApplyMixin");
        assertTrue(java.util.stream.StreamSupport.stream(plugin.instructions.spliterator(),false)
            .anyMatch(insn->insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && "Sable".equals(ldc.cst)));
        // Absent Sable never resolves the optional owner through the normal world-loader class.
        for (var method:read("qouteall/imm_ptl/core/ClientWorldLoader").methods) {
            for (var instruction:method.instructions) if (instruction instanceof MethodInsnNode call)
                assertFalse(call.owner.endsWith("SableSectionRenderOwner"));
        }
    }

    private static void assertBefore(MethodNode method,String first,String second) {
        int a=callIndex(method,first),b=callIndex(method,second);
        assertTrue(a>=0 && b>a,first+" must precede "+second+" in "+method.name);
    }
    private static int callIndex(MethodNode method,String name) {
        for (int i=0;i<method.instructions.size();i++)
            if (method.instructions.get(i) instanceof MethodInsnNode call && call.name.equals(name)) return i;
        return -1;
    }
    private static MethodNode method(String resource,String name) throws Exception {
        return read(resource).methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();
    }
    private static ClassNode read(String resource) throws Exception {
        try (InputStream stream=SableSectionOwnershipContractTest.class.getClassLoader().getResourceAsStream(resource+".class")) {
            assertNotNull(stream,"Missing class "+resource);
            var node=new ClassNode(); new ClassReader(stream).accept(node,0); return node;
        }
    }
}

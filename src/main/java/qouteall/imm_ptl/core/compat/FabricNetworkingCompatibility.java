package qouteall.imm_ptl.core.compat;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Check the injection ABI, rather than rejecting compatible bundled API versions. */
public final class FabricNetworkingCompatibility {
    private FabricNetworkingCompatibility() {}

    public static void check(ClassNode target, String version) {
        switch (target.name) {
            case "net/fabricmc/fabric/impl/networking/PayloadTypeRegistryImpl" -> {
                field(target, "packetTypes", version);
                invocation(target, "register", "put", version);
            }
            case "org/sinytra/fabric/networking_api/NeoNetworkRegistrar" -> {
                field(target, "registeredPayloads", version);
                invocation(target, "getOrRegisterNativeHandler", "computeIfAbsent", version);
            }
            case "org/sinytra/fabric/networking_api/NeoNetworkRegistrar$NeoPayloadHandler" -> {
                field(target, "globalReceivers", version);
                field(target, "localReceivers", version);
                method(target, "registerGlobalHandler", "(Lnet/minecraft/network/protocol/PacketFlow;Ljava/lang/Object;Ljava/util/function/Function;Lorg/apache/commons/lang3/function/TriConsumer;)Z", version);
                method(target, "registerLocalReceiver", "(Lnet/neoforged/neoforge/common/extensions/ICommonPacketListener;Ljava/lang/Object;Ljava/util/function/Function;Lorg/apache/commons/lang3/function/TriConsumer;)Z", version);
            }
            default -> { } // NeoForge targets are validated by their required mixin injections.
        }
    }

    private static void field(ClassNode target, String name, String version) {
        if (target.fields.stream().noneMatch(f -> f.name.equals(name) && f.desc.equals("Ljava/util/Map;")))
            throw unsupported(target, version, "Map field " + name);
    }

    private static void method(ClassNode target, String name, String descriptor, String version) {
        if (target.methods.stream().noneMatch(m -> m.name.equals(name) && m.desc.equals(descriptor)))
            throw unsupported(target, version, "method " + name + descriptor);
    }

    private static void invocation(ClassNode target, String name, String operation, String version) {
        long matches = 0;
        for (MethodNode method : target.methods) {
            if (!method.name.equals(name)) continue;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals("java/util/Map")
                    && call.name.equals(operation) && call.desc.equals(operation.equals("put")
                        ? "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                        : "(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;")) matches++;
            }
        }
        if (matches != 1) throw unsupported(target, version, "one Map." + operation + " call in " + name);
    }

    private static IllegalStateException unsupported(ClassNode target, String version, String requirement) {
        return new IllegalStateException("Immersive Portals: incompatible Forgified Fabric networking "
            + version + "; " + target.name + " requires " + requirement + ".");
    }
}

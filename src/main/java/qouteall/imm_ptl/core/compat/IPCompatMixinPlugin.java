package qouteall.imm_ptl.core.compat;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Set;
import java.util.HashSet;

public class IPCompatMixinPlugin implements IMixinConfigPlugin {
    private RendererCompatibility rendererCompatibility;
    private final Set<String> checkedTargets = new HashSet<>();

    private void requireTarget(String className, String implementation) {
        if (checkedTargets.contains(className)) return;
        try {
            MixinService.getService().getBytecodeProvider().getClassNode(className);
            checkedTargets.add(className);
        } catch (Exception exception) {
            throw new IllegalStateException("Immersive Portals: unsupported " + implementation
                + " installation; required compatibility target " + className + " is missing.", exception);
        }
    }

    private RendererCompatibility rendererCompatibility(LoadingModList modList) {
        if (rendererCompatibility == null) {
            rendererCompatibility = RendererCompatibility.select(
                modList.getModFileById("sodium") != null, modList.getModFileById("embeddium") != null,
                modList.getModFileById("iris") != null, modList.getModFileById("oculus") != null
            );
            rendererCompatibility.checkVersions(version(modList, "embeddium"), version(modList, "oculus"));
        }
        return rendererCompatibility;
    }

    private static String version(LoadingModList modList, String id) {
        return modList.getMods().stream().filter(mod -> mod.getModId().equals(id))
            .map(mod -> mod.getVersion().toString()).findFirst().orElse(null);
    }
    @Override
    public void onLoad(String mixinPackage) {
    
    }
    
    @Override
    public String getRefMapperConfig() {
        return null;
    }
    
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {


        LoadingModList modList = LoadingModList.get();
        if (mixinClassName.contains(".fabric.")) {
            if (modList.getModFileById("fabric_networking_api_v1") == null) return false;
            FabricReceiverRegistration.checkVersion(version(modList, "fabric_networking_api_v1"));
            requireTarget(targetClassName, "Forgified Fabric networking 4.2.2+a92978fd19");
            return true;
        }
        if (mixinClassName.contains(".iris.") || mixinClassName.contains(".sodium.")
            || mixinClassName.contains(".embeddium.") || mixinClassName.contains(".neoculus.")) {
            boolean applies = rendererCompatibility(modList).appliesTo(mixinClassName);
            if (applies && mixinClassName.contains(".neoculus.")) requireTarget(targetClassName, "NeOculus 1.8.7");
            return applies;
        }
        
        if (mixinClassName.contains("Flywheel")) {
            boolean flywheelLoaded =  modList.getModFileById("flywheel") != null;
            if (flywheelLoaded) requireTarget("dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl", "Flywheel (modern 1.0 API required)");
            return flywheelLoaded;
        }
        
        if (mixinClassName.contains("Sable")) {
            boolean sableLoaded = modList.getModFileById("sable") != null;
            return sableLoaded;
        }

        if (mixinClassName.contains("CardinalComp")) {
            boolean cardinalCompLoaded = modList.getModFileById("cardinal-components-base") != null;
            return cardinalCompLoaded;
        }
        
        return false;
    }
    
    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    
    }
    
    @Override
    public List<String> getMixins() {
        return null;
    }
    
    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    
    }
    
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    
    }
}

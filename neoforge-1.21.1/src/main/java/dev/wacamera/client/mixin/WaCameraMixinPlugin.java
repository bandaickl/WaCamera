package dev.wacamera.client.mixin;

import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Select terrain ownership before mixin application; never load optional renderer classes here. */
public final class WaCameraMixinPlugin implements IMixinConfigPlugin {
    private static boolean loaded(String id) {
        var mods = FMLLoader.getLoadingModList();
        return mods != null && mods.getModFileById(id) != null;
    }

    public static boolean hasSodiumRenderer() {
        return loaded("sodium") || loaded("embeddium") || loaded("rubidium");
    }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".VanillaLevelRendererMixin")) return !hasSodiumRenderer();
        if (mixinClassName.endsWith(".IrisCommonUniformsMixin") || mixinClassName.endsWith(".IrisExclusiveUniformsMixin"))
            return loaded("iris");
        return true;
    }

    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}

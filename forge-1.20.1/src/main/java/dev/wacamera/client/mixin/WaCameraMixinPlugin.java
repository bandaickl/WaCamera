package dev.wacamera.client.mixin;

import net.minecraftforge.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

/** Do not try to inject vanilla chunk internals into an overwritten terrain renderer. */
public final class WaCameraMixinPlugin implements IMixinConfigPlugin {
    public static boolean hasSodiumRenderer() {
        var mods = FMLLoader.getLoadingModList();
        return mods != null && (mods.getModFileById("embeddium") != null
                || mods.getModFileById("rubidium") != null || mods.getModFileById("sodium") != null);
    }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".DistantHorizonRendererMixin")) {
            var mods = FMLLoader.getLoadingModList();
            return mods != null && mods.getModFileById("distanthorizons") != null;
        }
        if (mixinClassName.endsWith(".IrisCommonUniformsMixin") || mixinClassName.endsWith(".IrisExclusiveUniformsMixin")) {
            var mods = FMLLoader.getLoadingModList();
            return mods != null && (mods.getModFileById("oculus") != null || mods.getModFileById("iris") != null);
        }
        return !mixinClassName.endsWith(".VanillaLevelRendererMixin") || !hasSodiumRenderer();
    }
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}

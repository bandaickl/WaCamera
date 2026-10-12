package dev.wacamera.client.mixin;

import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Water-biome transition state belongs to a viewpoint, not to the framebuffer. */
@Mixin(FogRenderer.class)
public interface FogRendererAccessor {
    @Accessor("targetBiomeFog") static int wacamera$getTarget() { throw new AssertionError(); }
    @Accessor("targetBiomeFog") static void wacamera$setTarget(int value) { throw new AssertionError(); }
    @Accessor("previousBiomeFog") static int wacamera$getPrevious() { throw new AssertionError(); }
    @Accessor("previousBiomeFog") static void wacamera$setPrevious(int value) { throw new AssertionError(); }
    @Accessor("biomeChangedTime") static long wacamera$getChanged() { throw new AssertionError(); }
    @Accessor("biomeChangedTime") static void wacamera$setChanged(long value) { throw new AssertionError(); }
}

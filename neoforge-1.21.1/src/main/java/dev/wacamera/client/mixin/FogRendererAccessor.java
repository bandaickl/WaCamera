package dev.wacamera.client.mixin;

import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FogRenderer.class)
public interface FogRendererAccessor {
    @Accessor("targetBiomeFog") static int wacamera$getTargetBiomeFog() { throw new AssertionError(); }
    @Accessor("targetBiomeFog") static void wacamera$setTargetBiomeFog(int value) { throw new AssertionError(); }
    @Accessor("previousBiomeFog") static int wacamera$getPreviousBiomeFog() { throw new AssertionError(); }
    @Accessor("previousBiomeFog") static void wacamera$setPreviousBiomeFog(int value) { throw new AssertionError(); }
    @Accessor("biomeChangedTime") static long wacamera$getBiomeChangedTime() { throw new AssertionError(); }
    @Accessor("biomeChangedTime") static void wacamera$setBiomeChangedTime(long value) { throw new AssertionError(); }
}

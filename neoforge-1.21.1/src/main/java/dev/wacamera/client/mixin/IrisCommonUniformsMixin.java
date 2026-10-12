package dev.wacamera.client.mixin;

import dev.wacamera.client.WaCameraClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep shader lighting at the lens while the actual camera entity remains the controllable player. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CommonUniforms", remap = false)
public abstract class IrisCommonUniformsMixin {
    @Inject(method = "getEyeBrightness()Lorg/joml/Vector2i;", at = @At("HEAD"), cancellable = true)
    private static void wacamera$eyeLight(CallbackInfoReturnable<Vector2i> cir) {
        if (!WaCameraClient.cameraActive()) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var pos = BlockPos.containing(mc.gameRenderer.getMainCamera().getPosition());
        cir.setReturnValue(new Vector2i(mc.level.getBrightness(LightLayer.BLOCK, pos) * 16,
                mc.level.getBrightness(LightLayer.SKY, pos) * 16));
    }

    @Inject(method = "getSkyColor()Lorg/joml/Vector3d;", at = @At("HEAD"), cancellable = true)
    private static void wacamera$sky(CallbackInfoReturnable<Vector3d> cir) {
        if (!WaCameraClient.cameraActive()) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var color = mc.level.getSkyColor(mc.gameRenderer.getMainCamera().getPosition(),
                mc.getTimer().getGameTimeDeltaPartialTick(true));
        cir.setReturnValue(new Vector3d(color.x, color.y, color.z));
    }
}

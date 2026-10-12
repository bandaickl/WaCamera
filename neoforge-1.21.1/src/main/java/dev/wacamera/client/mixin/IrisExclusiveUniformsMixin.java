package dev.wacamera.client.mixin;

import dev.wacamera.client.WaCameraClient;
import net.minecraft.client.Minecraft;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.IrisExclusiveUniforms", remap = false)
public abstract class IrisExclusiveUniformsMixin {
    @Inject(method = "getEyePosition()Lorg/joml/Vector3d;", at = @At("HEAD"), cancellable = true)
    private static void wacamera$eye(CallbackInfoReturnable<Vector3d> cir) {
        if (!WaCameraClient.cameraActive()) return;
        var pos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        cir.setReturnValue(new Vector3d(pos.x, pos.y, pos.z));
    }

    @Inject(method = "isFirstPersonCamera()Z", at = @At("HEAD"), cancellable = true)
    private static void wacamera$detached(CallbackInfoReturnable<Boolean> cir) {
        if (WaCameraClient.cameraActive()) cir.setReturnValue(false);
    }
}

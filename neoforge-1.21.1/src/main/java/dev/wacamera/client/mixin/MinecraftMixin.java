package dev.wacamera.client.mixin;

import dev.wacamera.client.WaCameraClient;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void wacamera$shutdown(CallbackInfo ci) { WaCameraClient.shutdown(); }

    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void wacamera$backgroundCaptureRate(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(WaCameraClient.backgroundFrameRateLimit(cir.getReturnValue()));
    }

    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void wacamera$recordingTarget(CallbackInfoReturnable<RenderTarget> cir) {
        if (WaCameraClient.isRecordingPass()) cir.setReturnValue(WaCameraClient.recordingTarget());
    }

    @Inject(method = "useShaderTransparency", at = @At("HEAD"), cancellable = true)
    private static void wacamera$recordingTransparency(CallbackInfoReturnable<Boolean> cir) {
        if (WaCameraClient.isRecordingPass()) cir.setReturnValue(false);
    }
}

package dev.wacamera.client.mixin;

import dev.wacamera.client.WaCameraClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.mojang.blaze3d.pipeline.RenderTarget;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void wacamera$shutdown(CallbackInfo ci) { WaCameraClient.shutdown(); }

    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void wacamera$backgroundRecordingFrameRate(CallbackInfoReturnable<Integer> cir) {
        if (WaCameraClient.isBackgroundRecording() && !Minecraft.getInstance().isWindowActive()) {
            // Keep rendering/polling readbacks while unfocused without changing the user's FPS setting.
            cir.setReturnValue(WaCameraClient.backgroundFrameRateLimit(cir.getReturnValueI()));
        }
    }

    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void wacamera$target(CallbackInfoReturnable<RenderTarget> cir) {
        if (WaCameraClient.isRecordingPass()) cir.setReturnValue(WaCameraClient.recordingTarget());
    }

    @Inject(method = "useShaderTransparency", at = @At("HEAD"), cancellable = true)
    private static void wacamera$fancyCapture(CallbackInfoReturnable<Boolean> cir) {
        if (WaCameraClient.isRecordingPass()) cir.setReturnValue(false);
    }
}

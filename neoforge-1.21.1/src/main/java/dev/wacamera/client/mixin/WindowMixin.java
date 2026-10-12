package dev.wacamera.client.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.wacamera.client.WaCameraClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shader renderers must see the dimensions of the current offscreen pass, not the desktop window. */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
    private void wacamera$width(CallbackInfoReturnable<Integer> cir) {
        if (!WaCameraClient.isRecordingPass()) return;
        var target = WaCameraClient.recordingTarget();
        if (target != null) cir.setReturnValue(target.viewWidth);
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void wacamera$height(CallbackInfoReturnable<Integer> cir) {
        if (!WaCameraClient.isRecordingPass()) return;
        var target = WaCameraClient.recordingTarget();
        if (target != null) cir.setReturnValue(target.viewHeight);
    }
}

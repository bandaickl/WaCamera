package dev.wacamera.client.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.wacamera.client.WaCameraClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional renderers that query the window size must see this pass's render-target dimensions. */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
    private void wacamera$width(CallbackInfoReturnable<Integer> cir) {
        var target = WaCameraClient.recordingTarget();
        if (WaCameraClient.isRecordingPass() && target != null) cir.setReturnValue(target.viewWidth);
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void wacamera$height(CallbackInfoReturnable<Integer> cir) {
        var target = WaCameraClient.recordingTarget();
        if (WaCameraClient.isRecordingPass() && target != null) cir.setReturnValue(target.viewHeight);
    }
}

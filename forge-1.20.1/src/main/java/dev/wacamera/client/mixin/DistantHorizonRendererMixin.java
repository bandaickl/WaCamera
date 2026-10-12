package dev.wacamera.client.mixin;

import dev.wacamera.client.DistantHorizonCaptureBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional DH 2.3.4 integration; selection lasts until the owner's outer rendering finally block. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.render.renderer.LodRenderer", remap = false)
public abstract class DistantHorizonRendererMixin {
    @Inject(method = { "drawLods", "drawDeferredLods" }, at = @At("HEAD"), remap = false)
    private void wacamera$selectRecordingBuffers(CallbackInfo callback) {
        DistantHorizonCaptureBuffers.beforeDraw(this);
    }
}

package dev.wacamera.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.wacamera.client.CaptureLevelRenderer;
import dev.wacamera.client.SodiumCompatibility;
import dev.wacamera.client.WaCameraClient;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin implements CaptureLevelRenderer {
    @Shadow @Final @Mutable private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
    @Shadow private Frustum cullingFrustum;
    @Shadow private Frustum capturedFrustum;
    @Shadow private boolean captureFrustum;
    @Shadow private PostChain transparencyChain;
    @Shadow private RenderTarget translucentTarget, itemEntityTarget, particlesTarget, weatherTarget, cloudsTarget;
    @Shadow private double xTransparentOld, yTransparentOld, zTransparentOld;
    @Unique private ObjectArrayList<SectionRenderDispatcher.RenderSection> wacamera$normalChunks;
    @Unique private Frustum wacamera$normalFrustum, wacamera$capturedFrustum;
    @Unique private boolean wacamera$captureFrustum;
    @Unique private PostChain wacamera$transparency;
    @Unique private RenderTarget[] wacamera$targets;
    @Unique private double[] wacamera$transparencyPosition;

    @Override public void wacamera$beginCapture() {
        // Sodium and Iris own their terrain/shadow lists. Only vanilla uses our separate visible list.
        if (!SodiumCompatibility.isPresent()) {
            wacamera$normalChunks = visibleSections;
            visibleSections = new ObjectArrayList<>();
        }
        wacamera$normalFrustum = cullingFrustum;
        wacamera$capturedFrustum = capturedFrustum;
        wacamera$captureFrustum = captureFrustum;
        capturedFrustum = null;
        captureFrustum = false;
        wacamera$transparency = transparencyChain;
        wacamera$targets = new RenderTarget[]{translucentTarget, itemEntityTarget, particlesTarget, weatherTarget, cloudsTarget};
        // Display post chains permanently reference the display FBO. The recording uses the vanilla Fancy path.
        transparencyChain = null;
        translucentTarget = itemEntityTarget = particlesTarget = weatherTarget = cloudsTarget = null;
        wacamera$transparencyPosition = new double[]{xTransparentOld, yTransparentOld, zTransparentOld};
    }

    @Override public void wacamera$endCapture() {
        if (wacamera$normalChunks != null) visibleSections = wacamera$normalChunks;
        wacamera$normalChunks = null;
        cullingFrustum = wacamera$normalFrustum;
        capturedFrustum = wacamera$capturedFrustum;
        captureFrustum = wacamera$captureFrustum;
        transparencyChain = wacamera$transparency;
        translucentTarget = wacamera$targets[0]; itemEntityTarget = wacamera$targets[1]; particlesTarget = wacamera$targets[2];
        weatherTarget = wacamera$targets[3]; cloudsTarget = wacamera$targets[4];
        xTransparentOld = wacamera$transparencyPosition[0]; yTransparentOld = wacamera$transparencyPosition[1]; zTransparentOld = wacamera$transparencyPosition[2];
    }

    @Inject(method = {"shouldShowEntityOutlines"}, at = @At("HEAD"), cancellable = true)
    private void wacamera$outlines(CallbackInfoReturnable<Boolean> cir) { if (WaCameraClient.isRecordingPass()) cir.setReturnValue(false); }

    @Inject(method = "renderDebug", at = @At("HEAD"), cancellable = true)
    private void wacamera$debug(CallbackInfo ci) { if (WaCameraClient.isRecordingPass()) ci.cancel(); }

    @Redirect(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;pollLightUpdates()V"))
    private void wacamera$lightQueue(ClientLevel world) { if (!WaCameraClient.isRecordingPass()) world.pollLightUpdates(); }

    @Redirect(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I"))
    private int wacamera$lightUpdates(LevelLightEngine engine) { return WaCameraClient.isRecordingPass() ? 0 : engine.runLightUpdates(); }

}

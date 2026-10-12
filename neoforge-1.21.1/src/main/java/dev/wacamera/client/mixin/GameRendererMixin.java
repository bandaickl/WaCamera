package dev.wacamera.client.mixin;

import dev.wacamera.client.WaCameraClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void wacamera$frameStart(CallbackInfo ci) { WaCameraClient.beforeRender(); }

    @Redirect(method = "render", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;pauseOnLostFocus:Z"))
    private boolean wacamera$keepRecordingOnFocusLoss(Options options) {
        // Override only this automatic-pause read; retain the user's option and manual pause behavior.
        return options.pauseOnLostFocus && !WaCameraClient.isBackgroundRecording();
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
    private void wacamera$capture(DeltaTracker delta, boolean renderWorld, CallbackInfo ci) {
        WaCameraClient.renderRecordingPass((GameRenderer)(Object)this, delta);
    }

    @Inject(method = "getMainCamera", at = @At("HEAD"), cancellable = true)
    private void wacamera$recordingCamera(CallbackInfoReturnable<Camera> cir) {
        if (WaCameraClient.isRecordingPass()) cir.setReturnValue(WaCameraClient.recordingCamera());
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"))
    private void wacamera$hud(Gui gui, GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (!WaCameraClient.cameraActive()) gui.render(graphics, deltaTracker);
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;flush()V"))
    private void wacamera$status(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.screen == null) {
            WaCameraClient.overlay(graphics);
        }
        graphics.flush();
    }

    @Inject(method = "getFov", at = @At("HEAD"), cancellable = true)
    private void wacamera$fov(CallbackInfoReturnable<Double> cir) {
        if (WaCameraClient.cameraActive() && WaCameraClient.currentPose() != null) cir.setReturnValue((double)WaCameraClient.currentPose().fov());
    }

    @Inject(method = {"bobHurt", "bobView", "renderItemInHand", "renderItemActivationAnimation", "renderConfusionOverlay"}, at = @At("HEAD"), cancellable = true)
    private void wacamera$cleanView(CallbackInfo ci) { if (WaCameraClient.cameraActive()) ci.cancel(); }

    @Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true)
    private void wacamera$outline(CallbackInfoReturnable<Boolean> cir) { if (WaCameraClient.cameraActive()) cir.setReturnValue(false); }
}

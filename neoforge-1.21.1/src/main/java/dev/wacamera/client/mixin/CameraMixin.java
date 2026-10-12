package dev.wacamera.client.mixin;

import dev.wacamera.client.WaCameraClient;
import dev.wacamera.core.CameraPose;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private boolean detached;
    @Shadow protected abstract void setPosition(double x, double y, double z);
    @Shadow protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "setup", at = @At("TAIL"))
    private void wacamera$pose(BlockGetter world, Entity entity, boolean thirdPerson, boolean mirrored, float partialTick, CallbackInfo ci) {
        if (WaCameraClient.isRecordingPass() && (Object) this != WaCameraClient.recordingCamera()) return;
        CameraPose p = WaCameraClient.sampleCamera(partialTick);
        if (p == null) return;
        setPosition(p.x(), p.y(), p.z());
        setRotation(p.yaw(), p.pitch());
        // The vanilla local-player drawing condition now includes the real player.
        // Minecraft.cameraEntity remains the player: movement packets and picking stay intact.
        detached = true;
    }
}

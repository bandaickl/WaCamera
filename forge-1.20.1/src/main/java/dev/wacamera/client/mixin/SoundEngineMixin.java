package dev.wacamera.client.mixin;

import dev.wacamera.client.WaCameraClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    @Redirect(method = "updateSource", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getPosition()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 wacamera$listenerPosition(Camera camera) {
        var player = Minecraft.getInstance().player;
        return WaCameraClient.cameraActive() && player != null ? player.getEyePosition(1) : camera.getPosition();
    }

    @Redirect(method = "updateSource", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getLookVector()Lorg/joml/Vector3f;"))
    private Vector3f wacamera$listenerLook(Camera camera) {
        var player = Minecraft.getInstance().player;
        if (!WaCameraClient.cameraActive() || player == null) return new Vector3f(camera.getLookVector());
        Vec3 v = player.getViewVector(1);
        return new Vector3f((float)v.x, (float)v.y, (float)v.z);
    }

    @Redirect(method = "updateSource", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getUpVector()Lorg/joml/Vector3f;"))
    private Vector3f wacamera$listenerUp(Camera camera) {
        var player = Minecraft.getInstance().player;
        if (!WaCameraClient.cameraActive() || player == null) return new Vector3f(camera.getUpVector());
        Vec3 v = player.getUpVector(1);
        return new Vector3f((float)v.x, (float)v.y, (float)v.z);
    }
}

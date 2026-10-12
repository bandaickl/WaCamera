package dev.wacamera.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import dev.wacamera.client.WaCameraClient;
import dev.wacamera.client.SmokeTestRunner;
import dev.wacamera.client.CameraCommands;
import dev.wacamera.client.CameraMarkerRenderer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = "wacamera", value = Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent public static void renderLevel(RenderLevelStageEvent event) { CameraMarkerRenderer.render(event); }
    @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) { CameraCommands.register(event.getDispatcher()); }
    public static final KeyMapping PANEL = new KeyMapping("key.wacamera.panel", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.wacamera");
    public static final KeyMapping STOP = new KeyMapping("key.wacamera.stop", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.wacamera");
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        SmokeTestRunner.tick();
        WaCameraClient.checkRecoveryNotice();
        while (STOP.consumeClick()) WaCameraClient.stop("wacamera.stopped");
        while (PANEL.consumeClick()) if (Minecraft.getInstance().screen == null) WaCameraClient.openPanel();
    }
    @EventBusSubscriber(modid = "wacamera", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent public static void register(RegisterKeyMappingsEvent event) { event.register(PANEL); event.register(STOP); }
    }
}

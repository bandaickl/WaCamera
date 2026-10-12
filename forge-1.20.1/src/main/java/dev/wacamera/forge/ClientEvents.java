package dev.wacamera.forge;

import com.mojang.blaze3d.platform.InputConstants;
import dev.wacamera.client.WaCameraClient;
import dev.wacamera.client.SmokeTestRunner;
import dev.wacamera.client.CameraCommands;
import dev.wacamera.client.CameraMarkerRenderer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = "wacamera", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEvents {
    public static final KeyMapping PANEL = new KeyMapping("key.wacamera.panel", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.wacamera");
    public static final KeyMapping STOP = new KeyMapping("key.wacamera.stop", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.wacamera");
    @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) { CameraCommands.register(event.getDispatcher()); }
    @SubscribeEvent public static void renderLevel(RenderLevelStageEvent event) { CameraMarkerRenderer.render(event); }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        SmokeTestRunner.tick();
        WaCameraClient.checkRecoveryNotice();
        while (STOP.consumeClick()) WaCameraClient.stop("wacamera.stopped");
        while (PANEL.consumeClick()) if (Minecraft.getInstance().screen == null) WaCameraClient.openPanel();
    }
    @Mod.EventBusSubscriber(modid = "wacamera", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent public static void register(RegisterKeyMappingsEvent event) { event.register(PANEL); event.register(STOP); }
    }
}

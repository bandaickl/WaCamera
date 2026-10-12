package dev.wacamera.client;

import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Optional integration with Sodium 0.6's terrain graph. Both world passes run Sodium's own setupTerrain;
 * mutable render lists are never copied between cameras. Iris remains responsible for its shadow lists.
 */
public final class SodiumCompatibility {
    private static final String RENDERER_CLASS = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer";
    private static Bridge bridge;

    private SodiumCompatibility() { }

    /** Matches the mixin plugin, including unsupported replacements that must fail validation explicitly. */
    public static boolean isPresent() { return rendererMod() != null; }

    /** Validate while a world is active, before the recorder process or recording targets are created. */
    public static void validate() { invalidateVisibility(); }

    /** Mark dirty before the recording pass reaches LevelRenderer.setupRender / Sodium.setupTerrain. */
    public static void beginCapture() { invalidateVisibility(); }

    /** Mark dirty in finally so the following player pass rebuilds its own visibility, including its FOV. */
    public static void endCapture() { invalidateVisibility(); }

    private static void invalidateVisibility() {
        if (!isPresent()) return;
        try {
            Bridge api = resolveBridge();
            // Query every pass: reloads and world changes can replace the renderer instance.
            Object renderer = api.instanceNullable.invoke(null);
            if (renderer == null || !api.rendererType.isInstance(renderer)) {
                throw new IllegalStateException("No active terrain renderer is attached to this world");
            }
            api.scheduleTerrainUpdate.invoke(renderer);
        } catch (ReflectiveOperationException | LinkageError | IllegalStateException failure) {
            throw incompatible(failure);
        }
    }

    private static Bridge resolveBridge() throws ReflectiveOperationException {
        if (bridge != null) return bridge;
        Class<?> renderer = Class.forName(RENDERER_CLASS, false, SodiumCompatibility.class.getClassLoader());
        Method instance = renderer.getMethod("instanceNullable");
        Method schedule = renderer.getMethod("scheduleTerrainUpdate");
        if (!Modifier.isStatic(instance.getModifiers()) || Modifier.isStatic(schedule.getModifiers())
                || !renderer.isAssignableFrom(instance.getReturnType()) || schedule.getReturnType() != void.class) {
            throw new NoSuchMethodException("Unsupported SodiumWorldRenderer instanceNullable/scheduleTerrainUpdate signatures");
        }
        bridge = new Bridge(renderer, instance, schedule);
        return bridge;
    }

    private static String rendererMod() {
        ModList mods = ModList.get();
        if (mods == null) return null;
        if (mods.isLoaded("sodium")) return "Sodium";
        if (mods.isLoaded("embeddium")) return "Embeddium";
        if (mods.isLoaded("rubidium")) return "Rubidium";
        return null;
    }

    private static IllegalStateException incompatible(Throwable failure) {
        Throwable detail = failure;
        if (failure instanceof InvocationTargetException invocation && invocation.getCause() != null)
            detail = invocation.getCause();
        String reason = detail.getMessage() == null ? detail.getClass().getSimpleName() : detail.getMessage();
        String renderer = rendererMod();
        return new IllegalStateException(Component.translatable("wacamera.error.renderer_api",
                renderer == null ? "Sodium" : renderer, reason).getString(), detail);
    }

    private record Bridge(Class<?> rendererType, Method instanceNullable, Method scheduleTerrainUpdate) { }
}

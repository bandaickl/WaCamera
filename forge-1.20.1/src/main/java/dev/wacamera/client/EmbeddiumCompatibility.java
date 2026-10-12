package dev.wacamera.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;

/**
 * Soft integration with the Embeddium/Rubidium/Sodium terrain renderer. Their ordinary LevelRenderer.setupRender
 * must run for BOTH views. Vanilla renderChunksInFrustum is not used by these renderers.
 *
 * This bridge deliberately does not snapshot renderLists: the lists reference mutable per-region arrays,
 * section visibility frame IDs and rebuild queues. Instead it forces the renderer to rebuild visibility
 * for each camera, including equal-position/equal-angle cameras with different projection/FOV.
 */
public final class EmbeddiumCompatibility {
    private static final String RENDERER_CLASS = "me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer";
    private static Bridge bridge;

    private EmbeddiumCompatibility() { }

    public static boolean isPresent() { return rendererMod() != null; }

    /** Call before starting any recorder process, while a living player and world are available. */
    public static void validate() {
        invalidateVisibility();
    }

    /** Call inside the capture try/finally, before LevelRenderer.renderLevel reaches setupRender. */
    public static void beginCapture() { invalidateVisibility(); }

    /**
     * Call from finally. The immediately following normal world pass must run ordinary setupRender.
     * Its own viewport and monotonically increasing Embeddium frame ID replace capture visibility.
     */
    public static void endCapture() { invalidateVisibility(); }

    private static void invalidateVisibility() {
        if (!isPresent()) return;
        Object renderer = currentRenderer();
        try { bridge.scheduleTerrainUpdate.invoke(renderer); }
        catch (ReflectiveOperationException | LinkageError failure) { throw incompatible(failure); }
    }

    private static Object currentRenderer() {
        try {
            Bridge api = resolveBridge();
            Object renderer = api.instanceNullable.invoke(null);
            if (renderer == null || !api.rendererType.isInstance(renderer)) {
                throw new IllegalStateException("No active terrain renderer is attached to this world");
            }
            return renderer;
        } catch (ReflectiveOperationException | LinkageError | IllegalStateException failure) {
            throw incompatible(failure);
        }
    }

    private static Bridge resolveBridge() throws ReflectiveOperationException {
        if (bridge != null) return bridge;
        // The dependency is optional: do not load its classes until its mod is present and recording is requested.
        Class<?> renderer = Class.forName(RENDERER_CLASS, false, EmbeddiumCompatibility.class.getClassLoader());
        Method instance = renderer.getMethod("instanceNullable");
        Method schedule = renderer.getMethod("scheduleTerrainUpdate");
        if (!Modifier.isStatic(instance.getModifiers()) || Modifier.isStatic(schedule.getModifiers())
                || !renderer.isAssignableFrom(instance.getReturnType()) || schedule.getReturnType() != void.class) {
            throw new NoSuchMethodException("Incompatible SodiumWorldRenderer instanceNullable/scheduleTerrainUpdate signatures");
        }
        bridge = new Bridge(renderer, instance, schedule);
        return bridge;
    }

    private static String rendererMod() {
        ModList mods = ModList.get();
        if (mods == null) return null;
        if (mods.isLoaded("embeddium")) return "Embeddium";
        if (mods.isLoaded("rubidium")) return "Rubidium";
        // Match the mixin plugin's renderer detection even when Sodium is loaded through Connector.
        // An incompatible Sodium API must reject recording explicitly instead of bypassing invalidation.
        if (mods.isLoaded("sodium")) return "Sodium";
        return null;
    }

    private static IllegalStateException incompatible(Throwable failure) {
        Throwable detail = failure;
        if (failure instanceof InvocationTargetException invocation && invocation.getCause() != null) detail = invocation.getCause();
        String reason = detail.getMessage() == null ? detail.getClass().getSimpleName() : detail.getMessage();
        String renderer = rendererMod();
        return new IllegalStateException(Component.translatable("wacamera.error.renderer_api",
                renderer == null ? "Embeddium/Rubidium" : renderer, reason).getString(), detail);
    }

    private record Bridge(Class<?> rendererType, Method instanceNullable, Method scheduleTerrainUpdate) { }
}

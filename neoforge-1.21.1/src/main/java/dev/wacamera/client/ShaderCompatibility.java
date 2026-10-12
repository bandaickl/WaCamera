package dev.wacamera.client;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

/** Optional Iris API lookup; ordinary rendering never loads Iris classes. */
public final class ShaderCompatibility {
    private static Method getInstance, isShaderPackInUse;
    private static boolean loggedApiFailure;

    private ShaderCompatibility() { }

    public static void validateRecording() {
        if (isShaderActive() && ModList.get().isLoaded("distanthorizons")) {
            // DH has process-wide framebuffer/override bindings. Sharing them between two views
            // is unsafe until the NeoForge-specific DH bridge has been implemented.
            throw new IllegalStateException(Component.translatable("wacamera.error.shader_dh").getString());
        }
    }

    public static boolean isShaderActive() {
        if (!ModList.get().isLoaded("iris")) return false;
        try {
            initializeApi();
            Object api = getInstance.invoke(null);
            if (api == null || !isShaderPackInUse.getDeclaringClass().isInstance(api))
                throw new ReflectiveOperationException("IrisApi returned an incompatible instance");
            Object result = isShaderPackInUse.invoke(api);
            if (!(result instanceof Boolean active))
                throw new ReflectiveOperationException("IrisApi returned an invalid shader state");
            return active;
        } catch (ReflectiveOperationException | LinkageError failure) {
            if (!loggedApiFailure) {
                System.getLogger("wacamera").log(System.Logger.Level.WARNING, "Could not read Iris shader state", failure);
                loggedApiFailure = true;
            }
            throw new IllegalStateException(Component.translatable("wacamera.error.shader_api").getString());
        }
    }

    private static void initializeApi() throws ReflectiveOperationException {
        if (getInstance != null) return;
        Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi", false,
                ShaderCompatibility.class.getClassLoader());
        Method getter = api.getMethod("getInstance");
        Method query = api.getMethod("isShaderPackInUse");
        if (!Modifier.isStatic(getter.getModifiers()) || Modifier.isStatic(query.getModifiers())
                || query.getReturnType() != boolean.class)
            throw new NoSuchMethodException("Incompatible IrisApi signatures");
        isShaderPackInUse = query;
        getInstance = getter;
    }
}

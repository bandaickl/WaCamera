package dev.wacamera.client;

import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Detect the actual shader pipeline at recording time, including changes made by the shader toggle key. */
public final class ShaderCompatibility {
    private static final String[] API_CLASSES = {
            "net.irisshaders.iris.api.v0.IrisApi",
            "net.coderbot.iris.api.v0.IrisApi"
    };
    private static Method getInstance;
    private static Method isShaderPackInUse;
    private static boolean loggedApiFailure;

    private ShaderCompatibility() { }

    /** Check that the installed shader API is readable before allocating recording resources. */
    public static void validateRecording() {
        isShaderActive();
    }

    public static boolean isShaderActive() {
        if (!ModList.get().isLoaded("oculus") && !ModList.get().isLoaded("iris")) return false;
        final boolean active;
        try {
            initializeApi();
            Object api = getInstance.invoke(null);
            if (api == null) throw new ReflectiveOperationException("IrisApi.getInstance returned null");
            if (!isShaderPackInUse.getDeclaringClass().isInstance(api))
                throw new ReflectiveOperationException("IrisApi.getInstance returned an incompatible API object");
            Object result = isShaderPackInUse.invoke(api);
            if (!(result instanceof Boolean)) throw new ReflectiveOperationException("IrisApi shader state is not boolean");
            active = (Boolean) result;
        } catch (ReflectiveOperationException | LinkageError failure) {
            if (!loggedApiFailure) {
                System.getLogger("wacamera").log(System.Logger.Level.WARNING, "Could not read Oculus/Iris shader pipeline state", failure);
                loggedApiFailure = true;
            }
            // Keep the translated message even for callers that unwrap exception causes for display.
            throw new IllegalStateException(Component.translatable("wacamera.error.shader_api").getString());
        }
        return active;
    }

    private static void initializeApi() throws ReflectiveOperationException {
        if (getInstance != null && isShaderPackInUse != null) return;
        ClassNotFoundException missing = null;
        for (String name : API_CLASSES) {
            final Class<?> api;
            try { api = Class.forName(name, false, ShaderCompatibility.class.getClassLoader()); }
            catch (ClassNotFoundException failure) { missing = failure; continue; }
            Method getter = api.getMethod("getInstance");
            Method query = api.getMethod("isShaderPackInUse");
            if (!Modifier.isStatic(getter.getModifiers()) || Modifier.isStatic(query.getModifiers())
                    || (query.getReturnType() != boolean.class && query.getReturnType() != Boolean.class))
                throw new NoSuchMethodException("Unsupported IrisApi method signatures");
            getInstance = getter;
            isShaderPackInUse = query;
            return;
        }
        throw missing == null ? new ClassNotFoundException("IrisApi") : missing;
    }
}

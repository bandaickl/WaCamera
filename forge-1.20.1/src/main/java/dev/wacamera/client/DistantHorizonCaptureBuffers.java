package dev.wacamera.client;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the DH render resources for one recording. DH 2.3.4 allocates new textures on a size change
 * without releasing the previous ones, so alternating the window and recording sizes must not use
 * the same resource fields. Terrain data and its buffer handler remain shared and are never closed.
 */
public final class DistantHorizonCaptureBuffers implements AutoCloseable {
    private static final String[] RESOURCE_FIELDS = {
            "cachedWidth", "cachedHeight", "lodRenderProgram", "quadIBO", "isSetupComplete",
            "framebuffer", "nullableColorTexture", "depthTexture", "usingMcFrameBuffer"
    };
    private static final String[] ACTIVE_FIELDS = { "activeFramebufferId", "activeColorTextureId", "activeDepthTextureId" };
    private static final int[] OWNED_FIELDS = { 2, 3, 5, 6, 7 };
    private static final String[] DESTROY_METHODS = { "free", "destroyAsync", "destroy", "destroy", "destroy" };
    private static DistantHorizonCaptureBuffers active;

    private final Map<Object, View> views = new IdentityHashMap<>();
    private final List<View> entered = new ArrayList<>();
    private Object[] normalActive, captureActive;
    private Api api;
    private boolean inFrame, closed;

    /** Enter before the offscreen world render; no DH classes are loaded if DH never draws. */
    public void beginFrame() {
        if (closed) throw new IllegalStateException("Distant Horizons capture buffers are closed");
        if (inFrame || active != null) throw new IllegalStateException("Distant Horizons capture frame is already active");
        inFrame = true;
        active = this;
    }

    /** Called by the optional DH mixin. Keep the recording resources selected through final composites. */
    public static void beforeDraw(Object renderer) {
        DistantHorizonCaptureBuffers session = active;
        if (session != null) session.select(renderer);
    }

    private void select(Object renderer) {
        try {
            if (api == null) api = new Api(renderer.getClass());
            if (normalActive == null) {
                normalActive = read(api.activeFields, null);
                if (captureActive != null) write(api.activeFields, null, captureActive);
            }
            View view = views.computeIfAbsent(renderer, value -> new View(value, defaults()));
            if (view.normal != null) return; // Shadow, opaque and deferred draws use the same capture resources.
            view.normal = read(api.resources, renderer);
            entered.add(view); // Install rollback before changing the first field.
            write(api.resources, renderer, view.capture);
        } catch (ReflectiveOperationException failure) {
            throw failure("select", failure);
        }
    }

    /** Always run in the outer world-render finally block, including when DH or shader drawing throws. */
    public void endFrame() {
        if (!inFrame) return;
        Throwable failure = null;
        try { if (normalActive != null) captureActive = read(api.activeFields, null); }
        catch (ReflectiveOperationException error) { failure = error; }
        for (int index = entered.size() - 1; index >= 0; --index) {
            View view = entered.get(index);
            Object[] previous = view.capture;
            try { view.capture = read(api.resources, view.renderer); }
            catch (ReflectiveOperationException error) { failure = append(failure, error); }
            try { write(api.resources, view.renderer, view.normal); }
            catch (ReflectiveOperationException error) { failure = append(failure, error); }
            finally { view.normal = null; }
            // Usually unchanged. Also release superseded capture textures if its target was resized.
            try { release(previous, view.capture); }
            catch (ReflectiveOperationException error) { failure = append(failure, error); }
        }
        try {
            if (normalActive != null) write(api.activeFields, null, normalActive);
        } catch (ReflectiveOperationException error) { failure = append(failure, error); }
        finally {
            entered.clear();
            normalActive = null;
            inFrame = false;
            if (active == this) active = null;
        }
        if (failure != null) throw failure("restore", failure);
    }

    /** Release only this recording's programs, index buffer, framebuffer and textures. */
    @Override public void close() {
        if (closed) return;
        Throwable failure = null;
        try { endFrame(); }
        catch (RuntimeException error) { failure = error; }
        for (View view : views.values()) {
            try { release(view.capture, defaults()); }
            catch (ReflectiveOperationException error) { failure = append(failure, error); }
        }
        views.clear();
        closed = true;
        if (failure != null) throw failure("close", failure);
    }

    private static Object[] defaults() {
        return new Object[] { 0, 0, null, null, false, null, null, null, false };
    }

    private static void release(Object[] previous, Object[] retained) throws ReflectiveOperationException {
        ReflectiveOperationException failure = null;
        Map<Object, Boolean> released = new IdentityHashMap<>();
        for (int index = 0; index < OWNED_FIELDS.length; ++index) {
            Object resource = previous[OWNED_FIELDS[index]];
            if (resource == null || resource == retained[OWNED_FIELDS[index]] || released.put(resource, true) != null) continue;
            try {
                Method destroy = resource.getClass().getMethod(DESTROY_METHODS[index]);
                destroy.invoke(resource);
            } catch (ReflectiveOperationException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private static Object[] read(Field[] fields, Object owner) throws IllegalAccessException {
        Object[] result = new Object[fields.length];
        for (int index = 0; index < fields.length; ++index) result[index] = fields[index].get(owner);
        return result;
    }

    private static void write(Field[] fields, Object owner, Object[] values) throws IllegalAccessException {
        IllegalAccessException failure = null;
        for (int index = 0; index < fields.length; ++index) {
            try { fields[index].set(owner, values[index]); }
            catch (IllegalAccessException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private static Throwable append(Throwable original, Throwable additional) {
        if (original == null) return additional;
        original.addSuppressed(additional);
        return original;
    }

    private static IllegalStateException failure(String operation, Throwable error) {
        Throwable cause = error;
        while (cause instanceof InvocationTargetException invocation && invocation.getCause() != null) cause = invocation.getCause();
        return new IllegalStateException("Distant Horizons recording buffers could not " + operation + ": " + cause, error);
    }

    private static final class Api {
        private final Field[] resources, activeFields;
        private Api(Class<?> renderer) throws ReflectiveOperationException {
            resources = fields(renderer, RESOURCE_FIELDS);
            List<Field> globals = new ArrayList<>(List.of(fields(renderer, ACTIVE_FIELDS)));
            try {
                Class<?> irisEvents = Class.forName("net.irisshaders.iris.compat.dh.LodRendererEvents", false,
                        DistantHorizonCaptureBuffers.class.getClassLoader());
                // With separate buffers no resize event fires when returning to an existing view.
                // Iris's translucent depth copy must therefore recover that view's remembered size.
                globals.addAll(List.of(fields(irisEvents, new String[] { "textureWidth", "textureHeight", "atTranslucent" })));
            } catch (ClassNotFoundException ignored) { /* DH also runs without Oculus. */ }
            activeFields = globals.toArray(Field[]::new);
        }

        private static Field[] fields(Class<?> type, String[] names) throws ReflectiveOperationException {
            Field[] fields = new Field[names.length];
            for (int index = 0; index < fields.length; ++index) {
                fields[index] = type.getDeclaredField(names[index]);
                if (!fields[index].trySetAccessible()) throw new IllegalAccessException("Cannot access " + fields[index]);
            }
            return fields;
        }
    }

    private static final class View {
        private final Object renderer;
        private Object[] capture, normal;
        private View(Object renderer, Object[] capture) { this.renderer = renderer; this.capture = capture; }
    }
}

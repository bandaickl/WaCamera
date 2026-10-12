package dev.wacamera.client;

import com.mojang.blaze3d.platform.GlStateManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraftforge.fml.ModList;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;

/**
 * A second Oculus pipeline, with its own gbuffer, shadows, temporal history and terrain programs.
 * All methods are render-thread only. The caller scopes the main-target/camera overrides and GL state,
 * and must enter ShaderCaptureState before this class (including its first shader compilation).
 */
public final class ShaderCapturePipeline implements AutoCloseable {
    private static Api api;
    private Object capturePipeline;
    private Object captureOverrides;
    private Object sourceManager, sourcePack, sourceNormalPipeline, sourceDimension;
    private int sourceVersion;
    private Object overridesOwner;
    private Scope scope;
    private StorageBindings storageBindings;
    private DhScope dhScope;

    /** Run before enabling the capture camera/target overrides: DH can reload Iris when its settings change. */
    public static void prepareFrame() {
        if (!ShaderCompatibility.isShaderActive() || !ModList.get().isLoaded("distanthorizons")) return;
        try { api().dh().checkFrame.invoke(null); }
        catch (ReflectiveOperationException | LinkageError failure) { throw incompatible("prepare Distant Horizons", failure); }
    }

    /** Check optional integration metadata without constructing shaders or altering the active pipeline. */
    public static void validate() {
        if (!ShaderCompatibility.isShaderActive()) return;
        try {
            Api bridge = api();
            Object manager = bridge.getManager.invoke(null);
            Object normal = bridge.pipeline.get(manager);
            if (!bridge.pipelineType.isInstance(normal))
                throw new IllegalStateException("The active shader pipeline is not an Oculus IrisRenderingPipeline");
            if (EmbeddiumCompatibility.isPresent()) bridge.terrain();
            if (ModList.get().isLoaded("distanthorizons")) bridge.dh();
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw incompatible("validate", failure);
        }
    }

    /**
     * Select the independent pipeline for one offscreen world pass. The recording target must already
     * be returned by Minecraft.getMainRenderTarget(), including while shaders are constructed here.
     * Returns false for ordinary rendering with shaders disabled.
     */
    public boolean enter() {
        if (scope != null || storageBindings != null || dhScope != null)
            throw new IllegalStateException("Shader capture pipeline is already entered");
        if (!ShaderCompatibility.isShaderActive()) {
            destroyOwned();
            return false;
        }
        try {
            Api bridge = api();
            Object manager = bridge.getManager.invoke(null);
            Object normal = bridge.pipeline.get(manager);
            if (!bridge.pipelineType.isInstance(normal))
                throw new IllegalStateException("The active shader pipeline changed before capture");
            Object pack = ((Optional<?>) bridge.getPack.invoke(null)).orElseThrow(
                    () -> new IllegalStateException("Oculus has no current shader pack"));
            Object dimension = bridge.getDimension.invoke(null);
            int version = ((Number) bridge.getVersion.invoke(manager)).intValue();
            // The constructor binds storage buffers, even when it merely clears the first 16 unused slots.
            // Snapshot before constructing or destroying anything, not just before the world draw.
            storageBindings = StorageBindings.capture(Math.max(16,
                    Math.max(bridge.storageSlots(normal), bridge.storageSlots(capturePipeline))));
            if (capturePipeline != null && (sourceManager != manager || sourcePack != pack
                    || sourceNormalPipeline != normal || !Objects.equals(sourceDimension, dimension)
                    || sourceVersion != version)) destroyOwned();

            if (capturePipeline == null) {
                // Iris.createPipeline catches compilation errors and globally disables shaders. Calling the
                // constructor directly keeps such errors local to recording and leaves the normal view alive.
                Object programs = bridge.getProgramSet.invoke(pack, dimension);
                capturePipeline = bridge.newPipeline.newInstance(programs);
                sourceManager = manager;
                sourcePack = pack;
                sourceNormalPipeline = normal;
                sourceDimension = dimension;
                sourceVersion = version;
            }

            Terrain terrain = EmbeddiumCompatibility.isPresent() ? bridge.terrain() : null;
            if (terrain != null && (captureOverrides == null || overridesOwner != terrain.owner)) {
                destroyOverrides();
                captureOverrides = bridge.newOverrides.newInstance();
                overridesOwner = terrain.owner;
            }

            @SuppressWarnings("unchecked")
            Map<Object, Object> pipelines = (Map<Object, Object>) bridge.pipelines.get(manager);
            Scope entered = new Scope(manager, normal, pipelines, dimension, pipelines.containsKey(dimension),
                    pipelines.get(dimension), terrain, terrain == null ? null : terrain.overrides.get(terrain.owner));
            // Install the rollback record before the first mutation, so a partial failure is reversible.
            scope = entered;
            if (ModList.get().isLoaded("distanthorizons")) {
                dhScope = bridge.dh().snapshot(normal, capturePipeline);
                dhScope.detach();
            }
            pipelines.put(dimension, capturePipeline);
            bridge.pipeline.set(manager, capturePipeline);
            if (terrain != null) terrain.overrides.set(terrain.owner, captureOverrides);
            Object storage = bridge.storageHolder.get(capturePipeline);
            if (storage != null) bridge.setupStorage.invoke(storage);
            clearProgramListeners();
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            try { exit(); }
            catch (RuntimeException restoration) { failure.addSuppressed(restoration); }
            throw incompatible("enter", failure);
        }
    }

    /** LevelRenderer finalizes the world composites; GameRenderer's otherwise skipped color conversion follows. */
    public void finish() {
        if (scope == null) return;
        try { api().finish.invoke(capturePipeline); }
        catch (ReflectiveOperationException | LinkageError failure) { throw incompatible("finalize", failure); }
    }

    /** Restore every reference even when a shader compilation or world draw failed. Safe to call twice. */
    public void exit() {
        Scope entered = scope;
        if (entered == null && storageBindings == null && dhScope == null) return;
        scope = null;
        Throwable failure = null;
        DhScope horizon = dhScope;
        dhScope = null;
        try { if (horizon != null) horizon.restore(); }
        catch (ReflectiveOperationException | RuntimeException error) { failure = error; }
        try {
            if (entered != null && entered.terrain != null)
                entered.terrain.overrides.set(entered.terrain.owner, entered.normalOverrides);
        } catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }
        try {
            if (entered != null) {
                if (entered.hadDimension) entered.pipelines.put(entered.dimension, entered.normalDimensionPipeline);
                else entered.pipelines.remove(entered.dimension);
            }
        } catch (RuntimeException error) { failure = append(failure, error); }
        try { if (entered != null) api().pipeline.set(entered.manager, entered.normalPipeline); }
        catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }
        StorageBindings bindings = storageBindings;
        storageBindings = null;
        try { if (bindings != null) bindings.restore(); }
        catch (RuntimeException error) { failure = append(failure, error); }
        try { clearProgramListeners(); }
        catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }
        if (failure != null) throw incompatible("restore", failure);
    }

    /** Destroy only resources created here. Call inside the owner's GL-state-preserving close scope. */
    @Override public void close() {
        Throwable failure = null;
        try { exit(); }
        catch (RuntimeException error) { failure = error; }
        try { destroyOwned(); }
        catch (RuntimeException error) { failure = append(failure, error); }
        if (failure != null) throw incompatible("close", failure);
    }

    private void destroyOwned() {
        // ShaderStorageBuffer.destroy explicitly unbinds its indexed slot, regardless of which view is bound.
        StorageBindings bindings = capturePipeline == null ? null : StorageBindings.capture(Integer.MAX_VALUE);
        Throwable failure = null;
        try { destroyOverrides(); }
        catch (RuntimeException error) { failure = error; }
        Object owned = capturePipeline;
        capturePipeline = null;
        sourceManager = sourcePack = sourceNormalPipeline = sourceDimension = null;
        if (owned != null) {
            try { api().destroy.invoke(owned); }
            catch (ReflectiveOperationException | LinkageError | RuntimeException error) { failure = append(failure, error); }
            // Oculus 1.8 omits the begin stage, shadow computes and color converter from destroy().
            // Inspect resource validity after its own cleanup so already-freed objects are never deleted twice.
            try { api().destroyExtras(owned); }
            catch (ReflectiveOperationException | LinkageError | RuntimeException error) { failure = append(failure, error); }
        }
        try { if (bindings != null) bindings.restore(); }
        catch (RuntimeException error) { failure = append(failure, error); }
        if (failure != null) throw incompatible("destroy", failure);
    }

    private void destroyOverrides() {
        Object owned = captureOverrides;
        captureOverrides = null;
        overridesOwner = null;
        if (owned == null) return;
        try { api().deleteOverrides.invoke(owned); }
        catch (ReflectiveOperationException | LinkageError failure) { throw incompatible("destroy terrain programs", failure); }
    }

    private static void clearProgramListeners() throws ReflectiveOperationException {
        Api bridge = api();
        bridge.clearUniforms.invoke(null);
        bridge.clearSamplers.invoke(null);
        // Oculus 1.8 ProgramImages has no global active-program cache to clear.
    }

    private static Api api() throws ReflectiveOperationException {
        if (api == null) api = new Api();
        return api;
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, ShaderCapturePipeline.class.getClassLoader());
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }

    private static Field fieldOfType(Class<?> owner, Class<?> fieldType) throws NoSuchFieldException {
        Field found = null;
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            for (Field candidate : current.getDeclaredFields()) {
                if (!Modifier.isStatic(candidate.getModifiers()) && candidate.getType() == fieldType) {
                    if (found != null) throw new NoSuchFieldException("Ambiguous " + fieldType.getName() + " on " + owner.getName());
                    candidate.setAccessible(true);
                    found = candidate;
                }
            }
        }
        if (found == null) throw new NoSuchFieldException(fieldType.getName() + " on " + owner.getName());
        return found;
    }

    private static Throwable append(Throwable original, Throwable additional) {
        if (original == null) return additional;
        original.addSuppressed(additional);
        return original;
    }

    private static IllegalStateException incompatible(String operation, Throwable failure) {
        Throwable detail = failure;
        while (detail instanceof InvocationTargetException invocation && invocation.getCause() != null)
            detail = invocation.getCause();
        String reason = detail.getMessage() == null ? detail.getClass().getSimpleName() : detail.getMessage();
        return new IllegalStateException("Oculus recording pipeline could not " + operation + ": " + reason, detail);
    }

    private static final class Api {
        private final Class<?> pipelineType, overridesType;
        private final Method getManager, getPack, getDimension, getVersion, getProgramSet, finish, destroy;
        private final Method clearUniforms, clearSamplers, deleteOverrides, setupStorage;
        private final Constructor<?> newPipeline, newOverrides;
        private final Field pipeline, pipelines, storageHolder, storageBuffers;
        private final Field beginRenderer, beginPasses, shadowComputes, colorConverter, resourceValid;
        private final Method destroyResource;
        private Method sodiumInstance;
        private Field sectionManager;
        private DhApi dh;

        private Api() throws ReflectiveOperationException {
            Class<?> iris = type("net.irisshaders.iris.Iris");
            Class<?> manager = type("net.irisshaders.iris.pipeline.PipelineManager");
            Class<?> pack = type("net.irisshaders.iris.shaderpack.ShaderPack");
            Class<?> dimension = type("net.irisshaders.iris.shaderpack.materialmap.NamespacedId");
            Class<?> programs = type("net.irisshaders.iris.shaderpack.programs.ProgramSet");
            pipelineType = type("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
            overridesType = type("net.irisshaders.iris.compat.sodium.impl.shader_overrides.IrisChunkProgramOverrides");
            getManager = iris.getMethod("getPipelineManager");
            getPack = iris.getMethod("getCurrentPack");
            getDimension = iris.getMethod("getCurrentDimension");
            getVersion = manager.getMethod("getVersionCounterForSodiumShaderReload");
            getProgramSet = pack.getMethod("getProgramSet", dimension);
            newPipeline = pipelineType.getConstructor(programs);
            newOverrides = overridesType.getConstructor();
            finish = pipelineType.getMethod("finalizeGameRendering");
            destroy = pipelineType.getMethod("destroy");
            deleteOverrides = overridesType.getMethod("deleteShaders");
            pipeline = field(manager, "pipeline");
            pipelines = field(manager, "pipelinesPerDimension");
            storageHolder = field(pipelineType, "shaderStorageBufferHolder");
            Class<?> storage = type("net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder");
            storageBuffers = field(storage, "buffers");
            setupStorage = storage.getMethod("setupBuffers");
            beginRenderer = field(pipelineType, "beginRenderer");
            beginPasses = field(type("net.irisshaders.iris.pipeline.CompositeRenderer"), "passes");
            shadowComputes = field(pipelineType, "shadowComputes");
            colorConverter = field(pipelineType, "colorSpaceConverter");
            Class<?> resource = type("net.irisshaders.iris.gl.GlResource");
            resourceValid = field(resource, "isValid");
            destroyResource = resource.getMethod("destroy");
            clearUniforms = type("net.irisshaders.iris.gl.program.ProgramUniforms").getMethod("clearActiveUniforms");
            clearSamplers = type("net.irisshaders.iris.gl.program.ProgramSamplers").getMethod("clearActiveSamplers");
        }

        private int storageSlots(Object renderer) throws IllegalAccessException {
            if (renderer == null) return 0;
            Object holder = storageHolder.get(renderer);
            Object buffers = holder == null ? null : storageBuffers.get(holder);
            return buffers == null ? 0 : Array.getLength(buffers);
        }

        private DhApi dh() throws ReflectiveOperationException {
            if (dh == null) dh = new DhApi(pipelineType);
            return dh;
        }

        /** Only receives the independent pipeline allocated by this owner, never a normal-view pipeline. */
        private void destroyExtras(Object owned) throws ReflectiveOperationException {
            List<Object> resources = new ArrayList<>();
            Throwable failure = null;
            try {
                Object begin = beginRenderer.get(owned);
                if (begin != null) {
                    for (Object pass : (Iterable<?>) beginPasses.get(begin)) {
                        resources.add(field(pass.getClass(), "program").get(pass));
                        addArray(resources, field(pass.getClass(), "computes").get(pass));
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException error) { failure = error; }
            try { addArray(resources, shadowComputes.get(owned)); }
            catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }

            Object dhInternal = null, dhGeneric = null;
            Field dhGenericField = null;
            try {
                if (ModList.get().isLoaded("distanthorizons")) {
                    DhApi horizon = dh();
                    Object compat = horizon.getCompat.invoke(owned);
                    dhInternal = compat == null ? null : horizon.getInstance.invoke(compat);
                    if (dhInternal != null) {
                        // Never clean the shared SHADERLESS instance or another view's compatibility object.
                        if (field(dhInternal.getClass(), "pipeline").get(dhInternal) != owned)
                            throw new IllegalStateException("Distant Horizons resource owner does not match capture pipeline");
                        resources.add(field(dhInternal.getClass(), "depthTexNoTranslucent").get(dhInternal));
                        dhGenericField = field(dhInternal.getClass(), "genericShader");
                        dhGeneric = dhGenericField.get(dhInternal);
                        // DH framebuffers are registered in RenderTargets/ShadowRenderTargets. Their owners
                        // already destroy them; only this independent depth copy and generic program are omitted.
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }

            Object converter = null;
            Field swapTexture = null;
            try {
                converter = colorConverter.get(owned);
                if (converter != null) {
                    String kind = converter.getClass().getName();
                    if (kind.equals("net.irisshaders.iris.pathways.colorspace.ColorSpaceFragmentConverter")) {
                        resources.add(field(converter.getClass(), "program").get(converter));
                        resources.add(field(converter.getClass(), "framebuffer").get(converter));
                        swapTexture = field(converter.getClass(), "swapTexture");
                    } else if (kind.equals("net.irisshaders.iris.pathways.colorspace.ColorSpaceComputeConverter")) {
                        resources.add(field(converter.getClass(), "program").get(converter));
                    }
                    // The shaderpack-provided color-correction converter is an anonymous no-op, with no GL resources.
                }
            } catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }

            Map<Object, Boolean> seen = new IdentityHashMap<>();
            for (Object resource : resources) {
                if (resource == null || seen.put(resource, true) != null) continue;
                try {
                    if (resourceValid.getBoolean(resource)) destroyResource.invoke(resource);
                } catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }
            }
            if (dhGeneric != null) {
                try {
                    int id = ((Number) dhGeneric.getClass().getMethod("getId").invoke(dhGeneric)).intValue();
                    // IrisGenericRenderProgram is not a GlResource and free() has no validity guard.
                    // GL_DELETE_STATUS also avoids repeating a deletion pending while a program is bound.
                    if (id != 0 && GL20.glIsProgram(id) && GL20.glGetProgrami(id, GL20.GL_DELETE_STATUS) == GL11.GL_FALSE)
                        dhGeneric.getClass().getMethod("free").invoke(dhGeneric);
                    dhGenericField.set(dhInternal, null);
                } catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }
            }
            if (swapTexture != null) {
                try {
                    int texture = swapTexture.getInt(converter);
                    // Reset the raw ID too: unlike Program/GlFramebuffer, it has no GlResource validity flag.
                    swapTexture.setInt(converter, 0);
                    if (texture != 0 && GL11.glIsTexture(texture)) GlStateManager._deleteTexture(texture);
                } catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }
            }
            if (failure != null) throw new ReflectiveOperationException("Could not release all Oculus capture extras", failure);
        }

        private static void addArray(List<Object> resources, Object values) {
            if (values == null) return;
            for (int i = 0; i < Array.getLength(values); ++i) resources.add(Array.get(values, i));
        }

        private Terrain terrain() throws ReflectiveOperationException {
            if (sodiumInstance == null) {
                Class<?> renderer = type("me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer");
                sodiumInstance = renderer.getMethod("instanceNullable");
                sectionManager = field(renderer, "renderSectionManager");
            }
            Object renderer = sodiumInstance.invoke(null);
            if (renderer == null) throw new IllegalStateException("No Embeddium world renderer is active");
            Object sections = sectionManager.get(renderer);
            if (sections == null) throw new IllegalStateException("No Embeddium terrain manager is active");
            Object chunks = field(sections.getClass(), "chunkRenderer").get(sections);
            if (chunks == null) throw new IllegalStateException("No Embeddium chunk renderer is active");
            // Find by type: Mixin may rename its private field when another mod has the same field name.
            return new Terrain(chunks, fieldOfType(chunks.getClass(), overridesType));
        }
    }

    /** Preserve binding ranges as well as IDs; other render mods may use glBindBufferRange. */
    private record StorageBindings(int generic, int[] ids, long[] offsets, long[] sizes) {
        private static StorageBindings capture(int requestedSlots) {
            var capabilities = GL.getCapabilities();
            if (!capabilities.OpenGL43 && !capabilities.GL_ARB_shader_storage_buffer_object) return null;
            int count = Math.min(requestedSlots, GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS));
            int[] ids = new int[count];
            long[] offsets = new long[count], sizes = new long[count];
            for (int i = 0; i < count; i++) {
                ids[i] = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, i);
                if (ids[i] != 0) {
                    offsets[i] = GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START, i);
                    sizes[i] = GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE, i);
                }
            }
            return new StorageBindings(GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING), ids, offsets, sizes);
        }

        private void restore() {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] != 0 && sizes[i] > 0)
                    GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, i, ids[i], offsets[i], sizes[i]);
                else GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, i, ids[i]);
            }
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, generic);
        }
    }

    private static final class DhApi {
        private final Object injector;
        private final Class<?>[] interfaces;
        private final Method get, bind, unbind, priority, checkFrame, getCompat, getInstance;
        private final Method genericShader, solidFramebuffer, shadowFramebuffer;
        private final Field shadowFrustum;
        private final int defaultPriority;

        private DhApi(Class<?> pipelineType) throws ReflectiveOperationException {
            Class<?> injectorType = type("com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector");
            Class<?> overrideType = type("com.seibel.distanthorizons.api.interfaces.override.IDhApiOverrideable");
            injector = injectorType.getField("INSTANCE").get(null);
            get = injectorType.getMethod("get", Class.class, int.class);
            bind = injectorType.getMethod("bind", Class.class, overrideType);
            unbind = injectorType.getMethod("unbind", Class.class, overrideType);
            priority = overrideType.getMethod("getPriority");
            // Oculus's frustums use this interface default, including a new frustum created during shadows.
            if (!priority.isDefault()) throw new NoSuchMethodException("Distant Horizons override priority default");
            Object probe = Proxy.newProxyInstance(overrideType.getClassLoader(), new Class<?>[]{overrideType},
                    (proxy, method, arguments) -> {
                        if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method,
                                arguments == null ? new Object[0] : arguments);
                        throw new UnsupportedOperationException(method.getName());
                    });
            defaultPriority = ((Number) priority.invoke(probe)).intValue();
            String rendering = "com.seibel.distanthorizons.api.interfaces.override.rendering.";
            interfaces = new Class<?>[]{type(rendering + "IDhApiGenericObjectShaderProgram"),
                    type(rendering + "IDhApiFramebuffer"), type(rendering + "IDhApiShadowCullingFrustum")};
            Class<?> compat = type("net.irisshaders.iris.compat.dh.DHCompat");
            Class<?> internal = type("net.irisshaders.iris.compat.dh.DHCompatInternal");
            checkFrame = compat.getMethod("checkFrame");
            getCompat = pipelineType.getMethod("getDHCompat");
            getInstance = compat.getMethod("getInstance");
            genericShader = internal.getMethod("getGenericShader");
            solidFramebuffer = internal.getMethod("getSolidFBWrapper");
            shadowFramebuffer = internal.getMethod("getShadowFBWrapper");
            shadowFrustum = field(type("net.irisshaders.iris.shadows.ShadowRenderer"), "FRUSTUM");
        }

        private DhScope snapshot(Object normalPipeline, Object recordingPipeline) throws ReflectiveOperationException {
            List<DhBinding> bindings = new ArrayList<>();
            for (Class<?> contract : interfaces) add(bindings, contract, defaultPriority);
            for (Object renderer : new Object[]{normalPipeline, recordingPipeline}) {
                Object compat = getCompat.invoke(renderer);
                Object internal = compat == null ? null : getInstance.invoke(compat);
                if (internal != null) {
                    add(bindings, interfaces[0], genericShader.invoke(internal));
                    add(bindings, interfaces[1], solidFramebuffer.invoke(internal));
                    add(bindings, interfaces[1], shadowFramebuffer.invoke(internal));
                }
            }
            add(bindings, interfaces[2], shadowFrustum.get(null));
            return new DhScope(this, bindings);
        }

        private void add(List<DhBinding> bindings, Class<?> contract, Object instance) throws ReflectiveOperationException {
            if (instance != null && contract.isInstance(instance))
                add(bindings, contract, ((Number) priority.invoke(instance)).intValue());
        }

        private void add(List<DhBinding> bindings, Class<?> contract, int value) throws ReflectiveOperationException {
            for (DhBinding slot : bindings) if (slot.contract == contract && slot.priority == value) return;
            bindings.add(new DhBinding(contract, value, get.invoke(injector, contract, value)));
        }
    }

    private record DhBinding(Class<?> contract, int priority, Object normal) { }
    private record DhScope(DhApi api, List<DhBinding> bindings) {
        private void detach() throws ReflectiveOperationException {
            for (DhBinding slot : bindings)
                if (slot.normal != null) api.unbind.invoke(api.injector, slot.contract, slot.normal);
        }

        private void restore() throws ReflectiveOperationException {
            ReflectiveOperationException failure = null;
            for (DhBinding slot : bindings) {
                try {
                    Object current = api.get.invoke(api.injector, slot.contract, slot.priority);
                    if (current == slot.normal) continue;
                    if (current != null) api.unbind.invoke(api.injector, slot.contract, current);
                    if (slot.normal != null) api.bind.invoke(api.injector, slot.contract, slot.normal);
                } catch (ReflectiveOperationException error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        }
    }

    private record Terrain(Object owner, Field overrides) { }
    private record Scope(Object manager, Object normalPipeline, Map<Object, Object> pipelines,
                         Object dimension, boolean hadDimension, Object normalDimensionPipeline,
                         Terrain terrain, Object normalOverrides) { }
}

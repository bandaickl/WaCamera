package dev.wacamera.client;

import com.mojang.blaze3d.platform.GlStateManager;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;

/**
 * An independent Iris 1.8.12 pipeline: gbuffer, shadow maps, temporal history and Sodium programs.
 * Reflection signatures were checked against Iris 1.8.12 / Sodium 0.6.13 for Minecraft 1.21.1.
 * Render-thread only. The owner enters ShaderCaptureState and scopes camera, target and GL state
 * before calling enter(), including the first shader compilation, and restores them after exit().
 */
public final class ShaderCapturePipeline implements AutoCloseable {
    private static Api api;
    private static boolean constructionFailed;
    private Object capturePipeline;
    private Object sourceManager, sourcePack, sourceNormalPipeline, sourceDimension;
    private int sourceVersion;
    private Scope scope;
    private StorageBindings storageBindings;

    public static void prepareFrame() { ShaderCompatibility.validateRecording(); }

    /** Validate the optional bridge before starting a recorder, without allocating shader resources. */
    public static void validate() {
        ShaderCompatibility.validateRecording();
        if (!ShaderCompatibility.isShaderActive()) return;
        checkConstructionFailure();
        try {
            Api bridge = api();
            Object manager = bridge.getManager.invoke(null);
            if (manager == null || !bridge.pipelineType.isInstance(bridge.pipeline.get(manager)))
                throw new IllegalStateException("No active IrisRenderingPipeline");
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw incompatible("validate", failure);
        }
    }

    /** Select the independent pipeline for one offscreen world pass; false means shaders are disabled. */
    public boolean enter() {
        if (scope != null || storageBindings != null)
            throw new IllegalStateException("Shader capture pipeline is already entered");
        ShaderCompatibility.validateRecording();
        if (!ShaderCompatibility.isShaderActive()) {
            destroyOwned();
            return false;
        }
        checkConstructionFailure();
        try {
            Api bridge = api();
            Object manager = bridge.getManager.invoke(null);
            Object normal = bridge.pipeline.get(manager);
            if (!bridge.pipelineType.isInstance(normal))
                throw new IllegalStateException("The active Iris pipeline changed before capture");
            Object pack = ((Optional<?>) bridge.getPack.invoke(null)).orElseThrow(
                    () -> new IllegalStateException("Iris has no current shader pack"));
            Object dimension = bridge.getDimension.invoke(null);
            int version = ((Number) bridge.getVersion.invoke(manager)).intValue();
            storageBindings = StorageBindings.capture(Math.max(16,
                    Math.max(bridge.storageSlots(normal), bridge.storageSlots(capturePipeline))));
            if (capturePipeline != null && (sourceManager != manager || sourcePack != pack
                    || sourceNormalPipeline != normal || !Objects.equals(sourceDimension, dimension)
                    || sourceVersion != version)) destroyOwned();

            if (capturePipeline == null) {
                // Calling Iris.createPipeline would globally disable shaders on a compilation error.
                // Construct directly so the normal view keeps its own pipeline and program resources.
                Object programs = bridge.getProgramSet.invoke(pack, dimension);
                List<Object> registeredBuffers = bridge.registeredBuffers();
                try {
                    capturePipeline = bridge.newPipeline.newInstance(programs);
                    bridge.detachOwnedBuffers(capturePipeline);
                } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                    // A failed constructor cannot return its partially initialized pipeline. At least
                    // reclaim its registered SSBOs without touching any pre-existing normal-view buffer.
                    if (capturePipeline == null) {
                        // Iris's constructor has no rollback for partial GL allocations. Repeated
                        // retries could exhaust VRAM; require a fresh game process after this failure.
                        constructionFailed = true;
                        try { bridge.releaseNewBuffers(registeredBuffers); }
                        catch (ReflectiveOperationException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                    }
                    throw failure;
                }
                sourceManager = manager;
                sourcePack = pack;
                sourceNormalPipeline = normal;
                sourceDimension = dimension;
                sourceVersion = version;
            }

            @SuppressWarnings("unchecked")
            Map<Object, Object> pipelines = (Map<Object, Object>) bridge.pipelines.get(manager);
            scope = new Scope(manager, normal, pipelines, dimension, pipelines.containsKey(dimension), pipelines.get(dimension));
            pipelines.put(dimension, capturePipeline);
            bridge.pipeline.set(manager, capturePipeline);
            // Iris 1.8.12's Sodium mixin reads getSodiumPrograms() from the current pipeline for
            // every terrain pass. Unlike Oculus 1.8.0, no shared chunk-program override needs swapping.
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

    /** LevelRenderer already runs composites; complete the color conversion normally done by GameRenderer. */
    public void finish() {
        if (scope == null) return;
        try { api().finish.invoke(capturePipeline); }
        catch (ReflectiveOperationException | LinkageError failure) { throw incompatible("finalize", failure); }
    }

    public void exit() {
        Scope entered = scope;
        if (entered == null && storageBindings == null) return;
        scope = null;
        Throwable failure = null;
        try {
            if (entered != null) {
                if (entered.hadDimension) entered.pipelines.put(entered.dimension, entered.normalDimensionPipeline);
                else entered.pipelines.remove(entered.dimension);
            }
        } catch (RuntimeException error) { failure = error; }
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

    /** Only destroy resources created here, inside the caller's GL-state-preserving close scope. */
    @Override public void close() {
        Throwable failure = null;
        try { exit(); }
        catch (RuntimeException error) { failure = error; }
        try { destroyOwned(); }
        catch (RuntimeException error) { failure = append(failure, error); }
        if (failure != null) throw incompatible("close", failure);
    }

    private void destroyOwned() {
        Object owned = capturePipeline;
        if (owned == null) return;
        capturePipeline = null;
        sourceManager = sourcePack = sourceNormalPipeline = sourceDimension = null;
        StorageBindings bindings = StorageBindings.capture(Integer.MAX_VALUE);
        Throwable failure = null;
        try { api().destroy.invoke(owned); }
        catch (ReflectiveOperationException | LinkageError | RuntimeException error) { failure = error; }
        // Iris 1.8.12 does not release begin/setup/shadow computes, color conversion or Sodium
        // programs in destroy(). Check each object's validity after its normal cleanup.
        try { api().destroyExtras(owned); }
        catch (ReflectiveOperationException | LinkageError | RuntimeException error) { failure = append(failure, error); }
        try { clearProgramListeners(); }
        catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }
        try { if (bindings != null) bindings.restore(); }
        catch (RuntimeException error) { failure = append(failure, error); }
        if (failure != null) throw incompatible("destroy", failure);
    }

    private static void clearProgramListeners() throws ReflectiveOperationException {
        api().clearUniforms.invoke(null);
        api().clearSamplers.invoke(null);
        // ProgramImages has no global active-program cache in Iris 1.8.12.
    }

    private static Api api() throws ReflectiveOperationException {
        if (api == null) api = new Api();
        return api;
    }

    private static void checkConstructionFailure() {
        if (constructionFailed) throw new IllegalStateException(net.minecraft.network.chat.Component
                .translatable("wacamera.error.shader_restart").getString());
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, ShaderCapturePipeline.class.getClassLoader());
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            try {
                Field result = current.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
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
        if (constructionFailed) reason = net.minecraft.network.chat.Component
                .translatable("wacamera.error.shader_restart").getString() + " (" + reason + ")";
        return new IllegalStateException("Iris recording pipeline could not " + operation + ": " + reason, failure);
    }

    private static final class Api {
        private final Class<?> pipelineType;
        private final Method getManager, getPack, getDimension, getVersion, getProgramSet, finish, destroy;
        private final Method clearUniforms, clearSamplers, setupStorage, destroyResource, destroyBuffer;
        private final Method sodiumHandleValid, deleteSodiumProgram;
        private final Constructor<?> newPipeline;
        private final Field pipeline, pipelines, storageHolder, storageBuffers, activeBuffers;
        private final Field beginRenderer, beginPasses, beginProgram, beginComputes, setupComputes, shadowComputes;
        private final Field colorConverter, resourceValid, sodiumPrograms, sodiumShaders, sodiumFramebuffers;

        private Api() throws ReflectiveOperationException {
            Class<?> iris = type("net.irisshaders.iris.Iris");
            Class<?> manager = type("net.irisshaders.iris.pipeline.PipelineManager");
            Class<?> pack = type("net.irisshaders.iris.shaderpack.ShaderPack");
            Class<?> dimension = type("net.irisshaders.iris.shaderpack.materialmap.NamespacedId");
            pipelineType = type("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
            getManager = iris.getMethod("getPipelineManager");
            getPack = iris.getMethod("getCurrentPack");
            getDimension = iris.getMethod("getCurrentDimension");
            getVersion = manager.getMethod("getVersionCounterForSodiumShaderReload");
            getProgramSet = pack.getMethod("getProgramSet", dimension);
            newPipeline = pipelineType.getConstructor(type("net.irisshaders.iris.shaderpack.programs.ProgramSet"));
            finish = pipelineType.getMethod("finalizeGameRendering");
            destroy = pipelineType.getMethod("destroy");
            pipeline = field(manager, "pipeline");
            pipelines = field(manager, "pipelinesPerDimension");
            storageHolder = field(pipelineType, "shaderStorageBufferHolder");
            Class<?> storage = type("net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder");
            storageBuffers = field(storage, "buffers");
            activeBuffers = field(storage, "ACTIVE_BUFFERS");
            setupStorage = storage.getMethod("setupBuffers");
            destroyBuffer = type("net.irisshaders.iris.gl.buffer.ShaderStorageBuffer").getDeclaredMethod("destroy");
            destroyBuffer.setAccessible(true);
            beginRenderer = field(pipelineType, "beginRenderer");
            beginPasses = field(type("net.irisshaders.iris.pipeline.CompositeRenderer"), "passes");
            Class<?> pass = type("net.irisshaders.iris.pipeline.CompositeRenderer$Pass");
            beginProgram = field(pass, "program");
            beginComputes = field(pass, "computes");
            setupComputes = field(pipelineType, "setup");
            shadowComputes = field(pipelineType, "shadowComputes");
            colorConverter = field(pipelineType, "colorSpaceConverter");
            Class<?> resource = type("net.irisshaders.iris.gl.GlResource");
            resourceValid = field(resource, "isValid");
            destroyResource = resource.getMethod("destroy");
            sodiumPrograms = field(pipelineType, "sodiumPrograms");
            Class<?> programs = type("net.irisshaders.iris.pipeline.programs.SodiumPrograms");
            sodiumShaders = field(programs, "shaders");
            sodiumFramebuffers = field(programs, "framebuffers");
            Class<?> sodiumObject = type("net.caffeinemc.mods.sodium.client.gl.GlObject");
            sodiumHandleValid = sodiumObject.getDeclaredMethod("isHandleValid");
            sodiumHandleValid.setAccessible(true);
            deleteSodiumProgram = type("net.caffeinemc.mods.sodium.client.gl.shader.GlProgram").getMethod("delete");
            clearUniforms = type("net.irisshaders.iris.gl.program.ProgramUniforms").getMethod("clearActiveUniforms");
            clearSamplers = type("net.irisshaders.iris.gl.program.ProgramSamplers").getMethod("clearActiveSamplers");
        }

        private int storageSlots(Object renderer) throws IllegalAccessException {
            if (renderer == null) return 0;
            Object holder = storageHolder.get(renderer);
            Object buffers = holder == null ? null : storageBuffers.get(holder);
            return buffers == null ? 0 : Array.getLength(buffers);
        }

        @SuppressWarnings("unchecked")
        private List<Object> bufferRegistry() throws IllegalAccessException {
            return (List<Object>) activeBuffers.get(null);
        }

        private List<Object> registeredBuffers() throws IllegalAccessException {
            return new ArrayList<>(bufferRegistry());
        }

        private void detachOwnedBuffers(Object owned) throws IllegalAccessException {
            Object holder = storageHolder.get(owned);
            if (holder == null) return;
            Object buffers = storageBuffers.get(holder);
            if (buffers == null) return;
            Map<Object, Boolean> ownedBuffers = new IdentityHashMap<>();
            for (int i = 0; i < Array.getLength(buffers); i++) ownedBuffers.put(Array.get(buffers, i), true);
            // Iris's global leak cleanup would otherwise free these during shader reload, outside
            // this pipeline's lifecycle; ShaderStorageBuffer.destroy is not safe to call twice.
            bufferRegistry().removeIf(ownedBuffers::containsKey);
        }

        private void releaseNewBuffers(List<Object> before) throws ReflectiveOperationException {
            Map<Object, Boolean> existing = new IdentityHashMap<>();
            for (Object buffer : before) existing.put(buffer, true);
            List<Object> registry = bufferRegistry();
            Throwable failure = null;
            for (Object buffer : new ArrayList<>(registry)) {
                if (existing.containsKey(buffer)) continue;
                registry.removeIf(candidate -> candidate == buffer);
                try { destroyBuffer.invoke(buffer); }
                catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }
            }
            if (failure != null) throw new ReflectiveOperationException("Could not release incomplete Iris buffers", failure);
        }

        /** This receives only our pipeline, never the player's or another dimension's pipeline. */
        private void destroyExtras(Object owned) throws ReflectiveOperationException {
            List<Object> resources = new ArrayList<>();
            List<Object> terrainPrograms = new ArrayList<>();
            Throwable failure = null;
            try {
                Object begin = beginRenderer.get(owned);
                if (begin != null) for (Object pass : (Iterable<?>) beginPasses.get(begin)) {
                    resources.add(beginProgram.get(pass));
                    addArray(resources, beginComputes.get(pass));
                }
                addArray(resources, setupComputes.get(owned));
                addArray(resources, shadowComputes.get(owned));
            } catch (ReflectiveOperationException | RuntimeException error) { failure = error; }
            try {
                Object programs = sodiumPrograms.get(owned);
                if (programs != null) {
                    resources.addAll(((Map<?, ?>) sodiumFramebuffers.get(programs)).values());
                    terrainPrograms.addAll(((Map<?, ?>) sodiumShaders.get(programs)).values());
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
                    // Shaderpack-provided color correction uses an anonymous no-op converter.
                }
            } catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }
            Map<Object, Boolean> seen = new IdentityHashMap<>();
            for (Object resource : resources) {
                if (resource == null || seen.put(resource, true) != null) continue;
                try { if (resourceValid.getBoolean(resource)) destroyResource.invoke(resource); }
                catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }
            }
            for (Object program : terrainPrograms) {
                if (program == null || seen.put(program, true) != null) continue;
                try { if ((Boolean) sodiumHandleValid.invoke(program)) deleteSodiumProgram.invoke(program); }
                catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }
            }
            if (swapTexture != null) {
                try {
                    int texture = swapTexture.getInt(converter);
                    swapTexture.setInt(converter, 0);
                    if (texture != 0 && GL11.glIsTexture(texture)) GlStateManager._deleteTexture(texture);
                } catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }
            }
            if (failure != null) throw new ReflectiveOperationException("Could not release all Iris capture resources", failure);
        }

        private static void addArray(List<Object> resources, Object values) {
            if (values != null) for (int i = 0; i < Array.getLength(values); i++) resources.add(Array.get(values, i));
        }
    }

    /** Preserve indexed binding ranges as well as IDs for packs using shader storage buffers. */
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

    private record Scope(Object manager, Object normalPipeline, Map<Object, Object> pipelines,
                         Object dimension, boolean hadDimension, Object normalDimensionPipeline) { }
}

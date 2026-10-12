package dev.wacamera.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBuffers;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Isolates Iris globals and advances a separate clock for each captured frame, not each display frame. */
public final class ShaderCaptureState {
    private List<Slot> sharedSlots, clockSlots, rendererSlots;
    private Object[] normalShared, normalClock, normalRenderer, recordingClock;
    private Object counter, timer, clockPipeline;
    private Method beginCounter, beginTimer, getManager, getPipeline, clearUniforms, clearSamplers;
    private Method restoreBlend, unlockDepthColor, setPolygonMode;
    private Field polygonMode;
    private ThreadLocal<Object> skipExtension;
    private Object normalSkipExtension;
    private int normalPolygonMode;
    private boolean normalSmartCull, entered, advanceClock;

    public void enter() { enter(true); }

    /** Destruction also touches Iris globals, including when the pack has just been disabled. */
    public void enterCleanup() {
        if (sharedSlots != null) enter(false);
    }

    private void enter(boolean advance) {
        if (entered) throw new IllegalStateException("Iris capture state is already entered");
        try {
            initialize();
            Minecraft mc = Minecraft.getInstance();
            normalShared = read(sharedSlots);
            normalClock = read(clockSlots);
            normalSkipExtension = skipExtension.get();
            normalPolygonMode = polygonMode.getInt(null);
            normalSmartCull = mc.smartCull;
            rendererSlots = new ArrayList<>();
            if (mc.levelRenderer != null) {
                for (Field field : mc.levelRenderer.getClass().getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && (field.getType() == RenderBuffers.class
                            || field.getType().getName().equals("net.irisshaders.iris.pipeline.WorldRenderingPipeline")))
                        rendererSlots.add(slot(field, mc.levelRenderer));
                }
            }
            normalRenderer = read(rendererSlots);
            Object normalPipeline = getPipeline.invoke(getManager.invoke(null));
            if (clockPipeline != normalPipeline) {
                recordingClock = null;
                clockPipeline = normalPipeline;
            }
            if (recordingClock == null) recordingClock = normalClock.clone();
            advanceClock = advance;
            // All rollback data is ready before any shared state is modified.
            entered = true;
            clearProgramListeners();
            if (advance) {
                write(clockSlots, recordingClock);
                // We call LevelRenderer directly, so Iris's GameRenderer.render HEAD never runs here.
                beginCounter.invoke(counter);
                beginTimer.invoke(timer, Util.getNanos());
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            try { exit(); }
            catch (RuntimeException restoration) { failure.addSuppressed(restoration); }
            throw new IllegalStateException("Could not isolate Iris recording state", failure);
        }
    }

    public void exit() {
        if (!entered) return;
        Throwable failure = null;
        try { if (advanceClock) recordingClock = read(clockSlots); }
        catch (ReflectiveOperationException | RuntimeException error) { failure = error; }
        try { clearProgramListeners(); }
        catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }
        // Iris normally unlocks these at the end of a pass. A failed draw may skip that hook.
        try { restoreBlend.invoke(null); }
        catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }
        try { unlockDepthColor.invoke(null); }
        catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }
        try { setPolygonMode.invoke(null, normalPolygonMode); }
        catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }
        try { write(sharedSlots, normalShared); }
        catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }
        try { write(clockSlots, normalClock); }
        catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }
        try { write(rendererSlots, normalRenderer); }
        catch (ReflectiveOperationException | RuntimeException error) { failure = append(failure, error); }
        finally {
            Minecraft.getInstance().smartCull = normalSmartCull;
            skipExtension.set(normalSkipExtension);
            entered = false;
            normalShared = normalClock = normalRenderer = null;
            rendererSlots = null;
        }
        if (failure != null) throw new IllegalStateException("Could not restore Iris recording state", failure);
    }

    @SuppressWarnings("unchecked")
    private void initialize() throws ReflectiveOperationException {
        if (sharedSlots != null) return;
        List<Slot> shared = new ArrayList<>(), clock = new ArrayList<>();
        addInstanceFields(shared, singleton("net.irisshaders.iris.uniforms.CapturedRenderingState"));
        addInstanceFields(shared, singleton("net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings"));
        Class<?> time = type("net.irisshaders.iris.uniforms.SystemTimeUniforms");
        counter = time.getField("COUNTER").get(null);
        timer = time.getField("TIMER").get(null);
        addInstanceFields(clock, counter);
        addInstanceFields(clock, timer);
        beginCounter = counter.getClass().getMethod("beginFrame");
        beginTimer = timer.getClass().getMethod("beginFrame", long.class);
        Class<?> shadow = type("net.irisshaders.iris.shadows.ShadowRenderer");
        for (String name : List.of("ACTIVE", "visibleBlockEntities", "renderDistance", "MODELVIEW", "PROJECTION", "FRUSTUM"))
            shared.add(slot(shadow.getDeclaredField(name), null));
        Class<?> renderSystem = type("net.irisshaders.iris.gl.IrisRenderSystem");
        for (String name : List.of("backupProjection", "polygonMode", "backupPolygonMode", "cullingState"))
            shared.add(slot(renderSystem.getDeclaredField(name), null));
        polygonMode = slot(renderSystem.getDeclaredField("polygonMode"), null).field;
        setPolygonMode = renderSystem.getMethod("setPolygonMode", int.class);
        shared.add(slot(type("net.irisshaders.iris.uniforms.IrisTimeUniforms").getDeclaredField("dateTime"), null));
        // These flags can otherwise remain set if a shadow/world draw fails midway through.
        for (String name : List.of("net.irisshaders.iris.vertices.ImmediateState",
                "net.irisshaders.iris.gl.blending.BlendModeStorage",
                "net.irisshaders.iris.gl.blending.DepthColorStorage"))
            for (Field field : type(name).getDeclaredFields())
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers()))
                    shared.add(slot(field, null));
        // Dynamic uniform listeners must stay cleared until the next program is activated.
        Class<?> gbuffer = type("net.irisshaders.iris.layer.GbufferPrograms");
        for (String name : List.of("entities", "blockEntities", "outline"))
            shared.add(slot(gbuffer.getDeclaredField(name), null));
        skipExtension = (ThreadLocal<Object>) type("net.irisshaders.iris.vertices.ImmediateState").getField("skipExtension").get(null);
        restoreBlend = type("net.irisshaders.iris.gl.blending.BlendModeStorage").getMethod("restoreBlend");
        unlockDepthColor = type("net.irisshaders.iris.gl.blending.DepthColorStorage").getMethod("unlockDepthColor");
        clearUniforms = type("net.irisshaders.iris.gl.program.ProgramUniforms").getMethod("clearActiveUniforms");
        clearSamplers = type("net.irisshaders.iris.gl.program.ProgramSamplers").getMethod("clearActiveSamplers");
        getManager = type("net.irisshaders.iris.Iris").getMethod("getPipelineManager");
        getPipeline = type("net.irisshaders.iris.pipeline.PipelineManager").getMethod("getPipelineNullable");
        sharedSlots = List.copyOf(shared);
        clockSlots = List.copyOf(clock);
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, ShaderCaptureState.class.getClassLoader());
    }

    private static Object singleton(String name) throws ReflectiveOperationException {
        return type(name).getField("INSTANCE").get(null);
    }

    private static void addInstanceFields(List<Slot> slots, Object owner) throws ReflectiveOperationException {
        for (Field field : owner.getClass().getDeclaredFields())
            if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers()))
                slots.add(slot(field, owner));
    }

    private static Slot slot(Field field, Object owner) throws IllegalAccessException {
        if (!field.trySetAccessible()) throw new IllegalAccessException("Cannot access " + field);
        return new Slot(field, owner);
    }

    private static Object[] read(List<Slot> slots) throws IllegalAccessException {
        Object[] values = new Object[slots.size()];
        for (int i = 0; i < values.length; i++) {
            Object value = slots.get(i).field.get(slots.get(i).owner);
            // Shadow matrices and fog vectors may be mutated in place during the second draw.
            if (value instanceof Matrix4fc matrix) value = new Matrix4f(matrix);
            else if (value instanceof Vector3dc vector) value = new Vector3d(vector);
            values[i] = value;
        }
        return values;
    }

    private static void write(List<Slot> slots, Object[] values) throws IllegalAccessException {
        IllegalAccessException failure = null;
        for (int i = 0; i < values.length; i++) {
            try { slots.get(i).field.set(slots.get(i).owner, values[i]); }
            catch (IllegalAccessException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private void clearProgramListeners() throws ReflectiveOperationException {
        try { clearUniforms.invoke(null); }
        finally { clearSamplers.invoke(null); }
    }

    private static Throwable append(Throwable original, Throwable next) {
        if (original == null) return next;
        original.addSuppressed(next);
        return original;
    }

    private record Slot(Field field, Object owner) { }
}

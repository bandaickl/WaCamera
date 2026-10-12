package dev.wacamera.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraftforge.fml.ModList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Per-view Oculus globals. The separate pipeline owns textures and history; this owns its frame clock. */
public final class ShaderCaptureState {
    private List<Slot> sharedSlots;
    private List<Slot> clockSlots;
    private Method clearUniforms, clearSamplers;
    private Object[] recordingClock;
    private Object[] normalShared, normalClock;
    private List<Slot> rendererSlots;
    private Object[] normalRenderer;
    private boolean normalSmartCull;
    private boolean entered;

    /** Call before selecting/constructing the recording pipeline, while on the Minecraft render thread. */
    public void enter() {
        if (entered) throw new IllegalStateException("Shader capture state is already entered");
        if (!ModList.get().isLoaded("oculus") && !ModList.get().isLoaded("iris")) return;
        try {
            initialize();
            Minecraft mc = Minecraft.getInstance();
            normalShared = read(sharedSlots);
            normalClock = read(clockSlots);
            normalSmartCull = mc.smartCull;
            rendererSlots = new ArrayList<>();
            // Oculus temporarily replaces this buffer during shadows; restore it even if a shadow draw fails.
            if (mc.levelRenderer != null) {
                for (Field field : mc.levelRenderer.getClass().getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && (field.getType() == RenderBuffers.class
                            || field.getType().getName().equals("net.irisshaders.iris.pipeline.WorldRenderingPipeline")))
                        rendererSlots.add(slot(field, mc.levelRenderer));
                }
            }
            normalRenderer = read(rendererSlots);
            if (recordingClock == null) recordingClock = normalClock.clone();
            entered = true;
            clearProgramListeners();
            write(clockSlots, recordingClock);
        } catch (ReflectiveOperationException | LinkageError failure) {
            if (entered) {
                try { exit(); }
                catch (RuntimeException restoration) { failure.addSuppressed(restoration); }
            }
            throw new IllegalStateException("Could not isolate Oculus shader state", failure);
        }
    }

    /** Call after restoring the normal pipeline, in the outermost rendering finally block. */
    public void exit() {
        if (!entered) return;
        ReflectiveOperationException failure = null;
        try { recordingClock = read(clockSlots); }
        catch (ReflectiveOperationException error) { failure = error; }
        try { clearProgramListeners(); }
        catch (ReflectiveOperationException error) { failure = append(failure, error); }
        try { write(sharedSlots, normalShared); }
        catch (ReflectiveOperationException error) { failure = append(failure, error); }
        try { write(clockSlots, normalClock); }
        catch (ReflectiveOperationException error) { failure = append(failure, error); }
        try { write(rendererSlots, normalRenderer); }
        catch (ReflectiveOperationException error) { failure = append(failure, error); }
        finally {
            Minecraft.getInstance().smartCull = normalSmartCull;
            entered = false;
            normalShared = normalClock = normalRenderer = null;
            rendererSlots = null;
        }
        if (failure != null) throw new IllegalStateException("Could not restore Oculus shader state", failure);
    }

    private void initialize() throws ReflectiveOperationException {
        if (sharedSlots != null) return;
        List<Slot> shared = new ArrayList<>();
        List<Slot> clock = new ArrayList<>();
        addInstanceFields(shared, singleton("net.irisshaders.iris.uniforms.CapturedRenderingState", "INSTANCE"));
        addInstanceFields(shared, singleton("net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings", "INSTANCE"));
        Class<?> time = type("net.irisshaders.iris.uniforms.SystemTimeUniforms");
        addInstanceFields(clock, time.getField("COUNTER").get(null));
        addInstanceFields(clock, time.getField("TIMER").get(null));
        Class<?> shadow = type("net.irisshaders.iris.shadows.ShadowRenderer");
        for (String name : List.of("ACTIVE", "visibleBlockEntities", "renderDistance", "MODELVIEW", "PROJECTION", "FRUSTUM"))
            shared.add(slot(shadow.getDeclaredField(name), null));
        shared.add(slot(type("net.irisshaders.iris.gl.IrisRenderSystem").getDeclaredField("backupProjection"), null));
        shared.add(slot(type("net.irisshaders.iris.uniforms.IrisTimeUniforms").getDeclaredField("dateTime"), null));
        clearUniforms = type("net.irisshaders.iris.gl.program.ProgramUniforms").getMethod("clearActiveUniforms");
        clearSamplers = type("net.irisshaders.iris.gl.program.ProgramSamplers").getMethod("clearActiveSamplers");
        sharedSlots = List.copyOf(shared);
        clockSlots = List.copyOf(clock);
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, ShaderCaptureState.class.getClassLoader());
    }

    private static Object singleton(String className, String field) throws ReflectiveOperationException {
        return type(className).getField(field).get(null);
    }

    private static void addInstanceFields(List<Slot> slots, Object instance) throws ReflectiveOperationException {
        for (Field field : instance.getClass().getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers()))
                slots.add(slot(field, instance));
        }
    }

    private static Slot slot(Field field, Object owner) throws IllegalAccessException {
        if (!field.trySetAccessible()) throw new IllegalAccessException("Cannot access " + field);
        return new Slot(field, owner);
    }

    private static Object[] read(List<Slot> slots) throws IllegalAccessException {
        Object[] values = new Object[slots.size()];
        for (int i = 0; i < values.length; i++) values[i] = slots.get(i).field.get(slots.get(i).owner);
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
        clearUniforms.invoke(null);
        clearSamplers.invoke(null);
    }

    private static ReflectiveOperationException append(ReflectiveOperationException current, ReflectiveOperationException next) {
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }

    private record Slot(Field field, Object owner) { }
}

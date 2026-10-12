package dev.wacamera.client.mixin;

import dev.wacamera.client.WaCameraClient;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Comparator;

/** Disabled by the plugin when a replacement terrain renderer owns these methods. */
@Mixin(LevelRenderer.class)
public abstract class VanillaLevelRendererMixin {
    @Shadow @Final private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
    @Shadow private ViewArea viewArea;
    @Shadow private ClientLevel level;
    @Shadow private SectionRenderDispatcher sectionRenderDispatcher;

    @Inject(method = "setupRender", at = @At("HEAD"), cancellable = true)
    private void wacamera$visibility(Camera camera, Frustum frustum, boolean captured, boolean spectator, CallbackInfo ci) {
        if (!WaCameraClient.isRecordingPass()) return;
        ci.cancel();
        if (viewArea == null) return;
        var position = camera.getPosition();
        // Keep the player's asynchronous occlusion graph intact; enumerate already loaded sections.
        var chunks = new java.util.ArrayList<SectionRenderDispatcher.RenderSection>();
        for (var chunk : viewArea.sections) if (frustum.isVisible(chunk.getBoundingBox())) chunks.add(chunk);
        chunks.sort(Comparator.comparingDouble(chunk -> chunk.getOrigin().distToCenterSqr(position.x, position.y, position.z)));
        RenderRegionCache regions = new RenderRegionCache();
        int scheduled = 0;
        for (var chunk : chunks) {
            visibleSections.add(chunk);
            if (scheduled < 4 && chunk.isDirty() && level.getLightEngine().lightOnInSection(SectionPos.of(chunk.getOrigin()))) {
                chunk.rebuildSectionAsync(sectionRenderDispatcher, regions);
                chunk.setNotDirty();
                ++scheduled;
            }
        }
    }

    @Inject(method = "compileSections", at = @At("HEAD"), cancellable = true)
    private void wacamera$compile(Camera camera, CallbackInfo ci) { if (WaCameraClient.isRecordingPass()) ci.cancel(); }

    @Redirect(method = "renderSectionLayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;resortTransparency(Lnet/minecraft/client/renderer/RenderType;Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;)Z"))
    private boolean wacamera$transparencySort(SectionRenderDispatcher.RenderSection chunk, RenderType type, SectionRenderDispatcher dispatcher) {
        return !WaCameraClient.isRecordingPass() && chunk.resortTransparency(type, dispatcher);
    }
}

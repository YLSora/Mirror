package com.mirror.mixin;

import com.mirror.client.EmbeddiumVisibilityContext;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.lists.VisibleChunkCollector", remap = false)
abstract class EmbeddiumVisibleChunkCollectorMixin {
    @Redirect(method = "visit", at = @At(value = "INVOKE", target =
            "Lme/jellysquid/mods/sodium/client/render/chunk/region/RenderRegion;getRenderList()"
                    + "Lme/jellysquid/mods/sodium/client/render/chunk/lists/ChunkRenderList;"), require = 1)
    private ChunkRenderList mirror$ownRegionLists(RenderRegion region) {
        return EmbeddiumVisibilityContext.renderList(region);
    }
}

package com.mirror.client;

import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Value signature of the actual traversal inputs, not the transient Viewport identity. */
public record EmbeddiumCameraKey(Matrix4f frustum, int x, int y, int z,
                                 float fractionX, float fractionY, float fractionZ,
                                 SectionPos seed, float distance, boolean occlusion, long epoch) {
    public static EmbeddiumCameraKey create(Viewport viewport, float distance, boolean occlusion) {
        Matrix4f matrix = ((EmbeddiumViewportState) (Object) viewport).mirror$getCullMatrix();
        // Custom shader frusta can implement predicates not described by a vanilla matrix.
        if (matrix == null) return null;
        CameraTransform camera = viewport.getTransform();
        Vec3 origin = MirrorPassContext.current().cullingOrigin();
        SectionPos seed = origin == null ? viewport.getChunkCoord() : SectionPos.of(
                ((int) Math.floor(origin.x)) >> 4, ((int) Math.floor(origin.y)) >> 4,
                ((int) Math.floor(origin.z)) >> 4);
        return new EmbeddiumCameraKey(matrix, camera.intX, camera.intY, camera.intZ,
                camera.fracX, camera.fracY, camera.fracZ, seed, distance, occlusion,
                EmbeddiumViewEpoch.current());
    }
}

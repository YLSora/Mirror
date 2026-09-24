package com.mirror.compat.mixin;

import com.mirror.compat.GrassFrame;
import com.mirror.compat.ThirdPartyFrame;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.leonardoinc22.shortgrass.client.render.GrassSectionCache", remap = false)
abstract class GrassCacheMixin {
    @Shadow private static int lastEvictSx;

    @Inject(method = "buildBudgeted", at = @At("HEAD"), cancellable = true, require = 1)
    private static void mirror$collect(ClientLevel level, int x, int y, int z, int radius, int vertical,
                                        Vec3 eye, long tick, boolean iris, boolean compute,
                                        TextureAtlasSprite blade, TextureAtlasSprite snow, Frustum frustum,
                                        CallbackInfo ci) {
        if (GrassFrame.maintaining()) return;
        GrassFrame.collect(level, x, y, z, radius, vertical, eye, tick, iris, compute, blade, snow, frustum);
        ci.cancel();
    }

    @Inject(method = "drainPendingDirtySections", at = @At("HEAD"), cancellable = true, require = 1)
    private static void mirror$deferDirty(CallbackInfo ci) {
        if (!GrassFrame.maintaining()) ci.cancel();
    }

    @Inject(method = "evictOutOfRange", at = @At("HEAD"), cancellable = true, require = 1)
    private static void mirror$unionEviction(CallbackInfo ci) {
        if (!GrassFrame.maintaining()) ci.cancel();
        else lastEvictSx = Integer.MIN_VALUE; // Union membership can change without its envelope moving.
    }

    @Inject(method = "inRange", at = @At("HEAD"), cancellable = true, require = 1)
    private static void mirror$inUnion(long key, int x, int y, int z, int radius, int vertical,
                                       CallbackInfoReturnable<Boolean> ci) {
        if (GrassFrame.maintaining()) ci.setReturnValue(GrassFrame.contains(key));
    }

    @Inject(method = "shouldRecheckLod", at = @At("HEAD"), cancellable = true, require = 1)
    private static void mirror$unionLod(CallbackInfoReturnable<Boolean> ci) {
        if (GrassFrame.maintaining()) ci.setReturnValue(GrassFrame.recheckLod());
    }

    @Inject(method = "horizontalSectionDistanceSqr", at = @At("HEAD"), cancellable = true, require = 1)
    private static void mirror$lodDistance(long key, Vec3 eye, CallbackInfoReturnable<Double> ci) {
        if (GrassFrame.maintaining()) ci.setReturnValue(GrassFrame.distanceSquared(key, true));
    }

    @Inject(method = "sectionDistanceSqr", at = @At("HEAD"), cancellable = true, require = 1)
    private static void mirror$priorityDistance(long key, Vec3 eye, CallbackInfoReturnable<Double> ci) {
        if (GrassFrame.maintaining()) ci.setReturnValue(GrassFrame.distanceSquared(key, false));
    }

    @ModifyVariable(method = "submitBuild", at = @At("HEAD"), argsOnly = true, require = 1)
    private static Vec3 mirror$meshLod(Vec3 eye, ClientLevel level, RenderRegionCache cache, long key,
                                       long tick, boolean iris, boolean compute,
                                       TextureAtlasSprite blade, TextureAtlasSprite snow, Vec3 originalEye) {
        return GrassFrame.maintaining() ? GrassFrame.lodEye(key) : eye;
    }

    @Inject(method = "submitBuild", at = @At("RETURN"), require = 1)
    private static void mirror$countSubmit(CallbackInfoReturnable<Boolean> ci) {
        if (ci.getReturnValueZ()) ThirdPartyFrame.count(ThirdPartyFrame.Work.GRASS_SUBMIT);
    }

    @Inject(method = "drainResults", at = @At("HEAD"), require = 1)
    private static void mirror$countUpload(CallbackInfo ci) {
        ThirdPartyFrame.count(ThirdPartyFrame.Work.GRASS_UPLOAD_DRAIN);
    }

    @Inject(method = "refreshSectionLighting", at = @At("HEAD"), require = 1)
    private static void mirror$countLighting(CallbackInfoReturnable<Boolean> ci) {
        ThirdPartyFrame.count(ThirdPartyFrame.Work.GRASS_LIGHT);
    }

    @Inject(method = "disposeAll", at = @At("HEAD"), require = 1)
    private static void mirror$clear(CallbackInfo ci) { GrassFrame.clear(); }
}

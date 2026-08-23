package com.mirror.common.enderman;

import com.mojang.authlib.GameProfile;
import com.mirror.common.MirrorBlock;
import com.mirror.common.MirrorBlockEntity;
import com.mirror.common.ScreenRect;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public final class MirrorEndermanObservationController extends AbstractEndermanObservationController {
    private static final float PLAYERS_TO_MIRROR_DIST = 20.0f;
    private static final float ENDERMEN_TO_MIRROR_DIST = 20.0f;
    private static final GameProfile MIRROR_PLAYER = new GameProfile(
            UUID.fromString("33242C44-27d9-1f22-3d27-99D2C45d1379"),
            "[MIRROR_ENDERMAN_PLAYER]");

    private final MirrorBlockEntity mirror;

    public MirrorEndermanObservationController(MirrorBlockEntity mirror) {
        this.mirror = mirror;
    }

    @Override
    protected ServerLevel level() {
        return mirror.getLevel() instanceof ServerLevel serverLevel ? serverLevel : null;
    }

    @Override
    protected GameProfile fakePlayerProfile() {
        return MIRROR_PLAYER;
    }

    @Override
    protected float playersScreenDist() {
        return PLAYERS_TO_MIRROR_DIST;
    }

    @Override
    protected float endermenSearchDist() {
        return ENDERMEN_TO_MIRROR_DIST;
    }

    @Override
    public boolean isInvalid() {
        return mirror.isRemoved() || level() == null;
    }

    @Override
    protected TickContext openTick() {
        if (isInvalid()) return null;
        ScreenRect screen = mirror.getScreenRect();
        return new TickContext(screen, mirror.getBlockPos(),
                (fakePlayer, hit) -> orientAtReflection(screen, fakePlayer, hit));
    }

    private static boolean orientAtReflection(ScreenRect screen, Player fakePlayer,
                                              ScreenSpectatorView hit) {
        Vec3 hitWorld = screen.localToWorld(hit.localHit());
        Vec3 playerView = hit.player().getViewVector(1.0F).normalize();
        Vec3 reflected = playerView.subtract(screen.normal().scale(
                2.0 * playerView.dot(screen.normal()))).normalize();

        Vec3 fakePlayerPosition = hitWorld.add(reflected.scale(0.8));
        float eyeHeight = fakePlayer.getEyeHeight();
        fakePlayer.setPos(fakePlayerPosition.x, fakePlayerPosition.y - eyeHeight, fakePlayerPosition.z);

        float yaw = (float) Math.toDegrees(Math.atan2(-reflected.x, reflected.z));
        double horizontal = Math.sqrt(reflected.x * reflected.x + reflected.z * reflected.z);
        float pitch = (float) -Math.toDegrees(Math.atan2(reflected.y, horizontal));
        fakePlayer.setYRot(yaw);
        fakePlayer.setYHeadRot(yaw);
        fakePlayer.setXRot(pitch);
        return true;
    }
}

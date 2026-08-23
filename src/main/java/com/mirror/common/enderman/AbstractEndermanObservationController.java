package com.mirror.common.enderman;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Shared server-side screen sight test used by mirror observation. */
public abstract class AbstractEndermanObservationController {
    protected record ScreenSpectatorView(Player player, Vec2 localHit, double distance) {
    }

    @FunctionalInterface
    protected interface FakePlayerOrienter {
        boolean orient(Player fakePlayer, ScreenSpectatorView hit);
    }

    protected record TickContext(com.mirror.common.ScreenRect screenBasis,
                                 BlockPos endermenAnchor,
                                 FakePlayerOrienter orient) {
    }

    @Nullable
    protected abstract ServerLevel level();

    protected abstract GameProfile fakePlayerProfile();

    protected abstract float playersScreenDist();

    protected abstract float endermenSearchDist();

    public abstract boolean isInvalid();

    @Nullable
    protected abstract TickContext openTick();

    public boolean isPlayerLookingAtEnderman(EnderMan enderman, Player player) {
        TickContext context = openTick();
        ServerLevel level = level();
        if (context == null || level == null) return false;

        ScreenSpectatorView view = getPlayerHit(player, context.screenBasis(), playersScreenDist());
        if (view == null) return false;

        Player fakePlayer = FakePlayerFactory.get(level, fakePlayerProfile());
        return !checkEndermenLookedAt(List.of(view), List.of(enderman), fakePlayer, context.orient()).isEmpty();
    }

    public boolean tick() {
        TickContext context = openTick();
        ServerLevel level = level();
        if (context == null || level == null) return false;

        List<ScreenSpectatorView> views = findPlayersLookingAtScreen(
                level.players(), context.screenBasis(), playersScreenDist());
        if (views.isEmpty()) return false;

        List<EnderMan> endermen = findEndermenNear(level, context.endermenAnchor(), endermenSearchDist());
        if (endermen.isEmpty()) return false;

        Player fakePlayer = FakePlayerFactory.get(level, fakePlayerProfile());
        List<EndermanLookResult> looks = checkEndermenLookedAt(views, endermen, fakePlayer, context.orient());
        boolean anyAnger = false;
        for (EndermanLookResult look : looks) {
            if (MirrorEndermanObservationGoal.anger(look.enderman(), look.player(), this)) {
                anyAnger = true;
            }
        }
        return anyAnger;
    }

    protected static List<ScreenSpectatorView> findPlayersLookingAtScreen(
            Collection<? extends Player> players, com.mirror.common.ScreenRect screen, float maxDist) {
        if (players.isEmpty()) return List.of();
        List<ScreenSpectatorView> result = new ArrayList<>();
        for (Player player : players) {
            if (player.isCreative()) continue;
            ScreenSpectatorView view = getPlayerHit(player, screen, maxDist);
            if (view != null) result.add(view);
        }
        return result;
    }

    protected static List<EnderMan> findEndermenNear(ServerLevel level, BlockPos anchor, float range) {
        Vec3 center = Vec3.atCenterOf(anchor);
        double rangeSq = (double) range * range;
        AABB bounds = new AABB(anchor).inflate(range);
        return level.getEntitiesOfClass(EnderMan.class, bounds,
                enderman -> enderman.distanceToSqr(center.x, center.y, center.z) < rangeSq);
    }

    protected static List<EndermanLookResult> checkEndermenLookedAt(
            List<ScreenSpectatorView> views, List<EnderMan> endermen, Player fakePlayer,
            FakePlayerOrienter orient) {
        List<EndermanLookResult> results = new ArrayList<>();
        for (ScreenSpectatorView view : views) {
            if (!orient.orient(fakePlayer, view)) continue;
            for (EnderMan enderman : endermen) {
                if (EndermanSight.isLookingAtMe(enderman, fakePlayer)) {
                    results.add(new EndermanLookResult(view.player(), enderman));
                }
            }
        }
        return results;
    }

    @Nullable
    protected static ScreenSpectatorView getPlayerHit(Player player,
                                                        com.mirror.common.ScreenRect screen,
                                                        float maxDist) {
        final double epsilon = 1e-6;
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 eyeToCenter = screen.center().subtract(eye);
        double distanceSq = eyeToCenter.lengthSqr();
        if (distanceSq < epsilon || distanceSq > (double) maxDist * maxDist) return null;

        eyeToCenter = eyeToCenter.scale(1.0 / Math.sqrt(distanceSq));
        if (eyeToCenter.dot(screen.normal()) > 0.0) return null;

        Vec3 view = player.getViewVector(1.0F).normalize();
        double denominator = view.dot(screen.normal());
        if (Math.abs(denominator) < epsilon) return null;

        double distance = screen.center().subtract(eye).dot(screen.normal()) / denominator;
        if (distance <= 0.0) return null;

        Vec3 hit = eye.add(view.scale(distance));
        Vec2 local = screen.projectLocal(hit);
        return local == null ? null : new ScreenSpectatorView(player, local, distance);
    }
}
